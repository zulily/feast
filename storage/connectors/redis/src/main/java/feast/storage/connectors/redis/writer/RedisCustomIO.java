/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2019 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.storage.connectors.redis.writer;

import com.google.protobuf.Duration;
import feast.proto.core.FeatureSetProto.EntitySpec;
import feast.proto.core.FeatureSetProto.FeatureSetSpec;
import feast.proto.core.FeatureSetProto.FeatureSpec;
import feast.proto.core.StoreProto.Store.*;
import feast.proto.storage.RedisProto.RedisKey;
import feast.proto.storage.RedisProto.RedisKey.Builder;
import feast.proto.types.FeatureRowProto.FeatureRow;
import feast.proto.types.FieldProto.Field;
import feast.proto.types.ValueProto;
import feast.storage.api.writer.FailedElement;
import feast.storage.api.writer.WriteResult;
import feast.storage.common.retry.Retriable;
import io.lettuce.core.RedisException;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisCustomIO {

  private static final int DEFAULT_BATCH_SIZE = 1000;
  private static final int DEFAULT_TIMEOUT = 2000;
  private static final Random rand = new Random();

  private static TupleTag<FeatureRow> successfulInsertsTag =
      new TupleTag<FeatureRow>("successfulInserts") {};
  private static TupleTag<FailedElement> failedInsertsTupleTag =
      new TupleTag<FailedElement>("failedInserts") {};

  private static final Logger log = LoggerFactory.getLogger(RedisCustomIO.class);

  private RedisCustomIO() {}

  public static Write write(
      RedisIngestionClient redisIngestionClient,
      Map<String, FeatureSetSpec> featureSetSpecs,
      boolean enableRedisTtl,
      int maxRedisTtlJitterSeconds) {
    return new Write(
        redisIngestionClient, featureSetSpecs, enableRedisTtl, maxRedisTtlJitterSeconds);
  }

  // For unit testing the redis TTL jitter.
  static void setRandomSeed(long seed) {
    rand.setSeed(seed);
  }

  /** ServingStoreWrite data to a Redis server. */
  public static class Write extends PTransform<PCollection<FeatureRow>, WriteResult> {

    private Map<String, FeatureSetSpec> featureSetSpecs;
    private RedisIngestionClient redisIngestionClient;
    private int batchSize;
    private int timeout;
    private final boolean enableRedisTtl;
    private final int maxRedisTtlJitterSeconds;

    public Write(
        RedisIngestionClient redisIngestionClient,
        Map<String, FeatureSetSpec> featureSetSpecs,
        boolean enableRedisTtl,
        int maxRedisTtlJitterSeconds) {
      this.redisIngestionClient = redisIngestionClient;
      this.featureSetSpecs = featureSetSpecs;
      this.enableRedisTtl = enableRedisTtl;
      this.maxRedisTtlJitterSeconds = maxRedisTtlJitterSeconds;
    }

    public Write withBatchSize(int batchSize) {
      this.batchSize = batchSize;
      return this;
    }

    public Write withTimeout(int timeout) {
      this.timeout = timeout;
      return this;
    }

    @Override
    public WriteResult expand(PCollection<FeatureRow> input) {
      PCollectionTuple redisWrite =
          input.apply(
              ParDo.of(
                      new WriteDoFn(
                          redisIngestionClient,
                          featureSetSpecs,
                          enableRedisTtl,
                          maxRedisTtlJitterSeconds))
                  .withOutputTags(successfulInsertsTag, TupleTagList.of(failedInsertsTupleTag)));
      return WriteResult.in(
          input.getPipeline(),
          redisWrite.get(successfulInsertsTag),
          redisWrite.get(failedInsertsTupleTag));
    }

    public static class WriteDoFn extends DoFn<FeatureRow, FeatureRow> {

      private final List<FeatureRow> featureRows = new ArrayList<>();
      private final List<FeatureRow> skippedFeatureRows = new ArrayList<>();
      private Map<String, FeatureSetSpec> featureSetSpecs;
      private int batchSize = DEFAULT_BATCH_SIZE;
      private int timeout = DEFAULT_TIMEOUT;
      private RedisIngestionClient redisIngestionClient;
      private final boolean enableRedisTtl;
      private final int maxRedisTtlJitterSeconds;

      // Used by unit tests to avoid race condition
      static Supplier<ZonedDateTime> currentTime = ZonedDateTime::now;

      WriteDoFn(
          RedisIngestionClient redisIngestionClient,
          Map<String, FeatureSetSpec> featureSetSpecs,
          boolean enableRedisTtl,
          int maxRedisTtlJitterSeconds) {

        this.redisIngestionClient = redisIngestionClient;
        this.featureSetSpecs = featureSetSpecs;
        this.enableRedisTtl = enableRedisTtl;
        this.maxRedisTtlJitterSeconds = maxRedisTtlJitterSeconds;
      }

      public WriteDoFn withBatchSize(int batchSize) {
        if (batchSize > 0) {
          this.batchSize = batchSize;
        }
        return this;
      }

      public WriteDoFn withTimeout(int timeout) {
        if (timeout > 0) {
          this.timeout = timeout;
        }
        return this;
      }

      @Setup
      public void setup() {
        this.redisIngestionClient.setup();
      }

      @StartBundle
      public void startBundle() {
        try {
          redisIngestionClient.connect();
        } catch (RedisException e) {
          log.error("Connection to redis cannot be established ", e);
        }
        featureRows.clear();
      }

      private void executeBatch() throws Exception {
        this.redisIngestionClient
            .getBackOffExecutor()
            .execute(
                new Retriable() {
                  @Override
                  public void execute() throws ExecutionException, InterruptedException {
                    if (!redisIngestionClient.isConnected()) {
                      redisIngestionClient.connect();
                    }
                    featureRows.forEach(
                        row -> {
                          if (!setFeatureRow(
                              row,
                              redisIngestionClient,
                              featureSetSpecs.get(row.getFeatureSet()),
                              enableRedisTtl,
                              maxRedisTtlJitterSeconds,
                              getKey(row),
                              getValue(row))) {
                            skippedFeatureRows.add(row);
                          }
                        });
                    redisIngestionClient.sync();
                  }

                  @Override
                  public Boolean isExceptionRetriable(Exception e) {
                    return e instanceof RedisException;
                  }

                  @Override
                  public void cleanUpAfterFailure() {}
                });
      }

      // Calculate the TTL if necessary and write to redis.
      // Return true if the row was written and false otherwise.
      private static boolean setFeatureRow(
          FeatureRow row,
          RedisIngestionClient redisIngestionClient,
          FeatureSetSpec spec,
          boolean enableRedisTtl,
          int maxRedisTtlJitterSeconds,
          byte[] key,
          byte[] value) {
        long ttlSeconds = 0;
        boolean writeToRedis = true;
        if (enableRedisTtl && spec != null) {
          final Duration maxAge = spec.getMaxAge();

          // If maxAge isn't set don't apply TTL
          if (!maxAge.equals(Duration.getDefaultInstance())) {
            ttlSeconds = maxAge.getSeconds();

            // Adjust initial TTL based on event timestamp of FeatureRow
            ttlSeconds -=
                currentTime.get().toInstant().getEpochSecond()
                    - row.getEventTimestamp().getSeconds();

            // The consideration to write data to Redis or not should NOT take jitter into account
            if (ttlSeconds <= 0) {
              writeToRedis = false;
            } else {
              if (maxRedisTtlJitterSeconds > 0) {
                ttlSeconds += rand.nextInt(maxRedisTtlJitterSeconds);
              }
            }
          } else {
            // Log an error instead of throwing an exception since setting a TTL is an
            // optimization
            // for storage space usage.
            // Ensuring data is in Redis for serving even with no TTL is more important??
            log.error("Unable to find FeatureSet to set Redis TTL  featureSet={}", spec.getName());
          }
        }
        if (writeToRedis) {
          if (ttlSeconds > 0) {
            redisIngestionClient.setex(key, ttlSeconds, value);
          } else {
            redisIngestionClient.set(key, value);
          }
        } else {
          log.info("Not writing FeatureRow to Redis  key={}", key);
          return false;
        }
        return true;
      }

      private FailedElement toFailedElement(
          FeatureRow featureRow, Exception exception, String jobName) {
        return FailedElement.newBuilder()
            .setJobName(jobName)
            .setTransformName("RedisCustomIO")
            .setPayload(featureRow.toString())
            .setErrorMessage(exception.getMessage())
            .setStackTrace(ExceptionUtils.getStackTrace(exception))
            .build();
      }

      private FailedElement processSkippedFeatureRow(FeatureRow featureRow, String jobName) {
        return FailedElement.newBuilder()
            .setJobName(jobName)
            .setJobName(jobName)
            .setTransformName("RedisCustomIO")
            .setPayload(featureRow.toString())
            .setErrorMessage("featurerow_to_redis_skipped")
            .build();
      }

      private byte[] getKey(FeatureRow featureRow) {
        FeatureSetSpec featureSetSpec = featureSetSpecs.get(featureRow.getFeatureSet());
        List<String> entityNames =
            featureSetSpec.getEntitiesList().stream()
                .map(EntitySpec::getName)
                .sorted()
                .collect(Collectors.toList());

        Map<String, Field> entityFields = new HashMap<>();
        Builder redisKeyBuilder = RedisKey.newBuilder().setFeatureSet(featureRow.getFeatureSet());
        for (Field field : featureRow.getFieldsList()) {
          if (entityNames.contains(field.getName())) {
            entityFields.putIfAbsent(
                field.getName(),
                Field.newBuilder().setName(field.getName()).setValue(field.getValue()).build());
          }
        }
        for (String entityName : entityNames) {
          redisKeyBuilder.addEntities(entityFields.get(entityName));
        }
        return redisKeyBuilder.build().toByteArray();
      }

      private byte[] getValue(FeatureRow featureRow) {
        FeatureSetSpec spec = featureSetSpecs.get(featureRow.getFeatureSet());

        List<String> featureNames =
            spec.getFeaturesList().stream().map(FeatureSpec::getName).collect(Collectors.toList());
        Map<String, Field> fieldValueOnlyMap =
            featureRow.getFieldsList().stream()
                .filter(field -> featureNames.contains(field.getName()))
                .distinct()
                .collect(
                    Collectors.toMap(
                        Field::getName,
                        field -> Field.newBuilder().setValue(field.getValue()).build()));

        List<Field> values =
            featureNames.stream()
                .sorted()
                .map(
                    featureName ->
                        fieldValueOnlyMap.getOrDefault(
                            featureName,
                            Field.newBuilder()
                                .setValue(ValueProto.Value.getDefaultInstance())
                                .build()))
                .collect(Collectors.toList());

        return FeatureRow.newBuilder()
            .setEventTimestamp(featureRow.getEventTimestamp())
            .addAllFields(values)
            .build()
            .toByteArray();
      }

      @ProcessElement
      public void processElement(ProcessContext context) {
        FeatureRow featureRow = context.element();
        featureRows.add(featureRow);
        if (featureRows.size() >= batchSize) {
          try {
            executeBatch();
            featureRows.forEach(row -> context.output(successfulInsertsTag, row));
            featureRows.clear();
            skippedFeatureRows.forEach(
                row -> processSkippedFeatureRow(row, context.getPipelineOptions().getJobName()));
            skippedFeatureRows.clear();
          } catch (Exception e) {
            featureRows.forEach(
                failedMutation -> {
                  FailedElement failedElement =
                      toFailedElement(failedMutation, e, context.getPipelineOptions().getJobName());
                  context.output(failedInsertsTupleTag, failedElement);
                });
            featureRows.clear();
          }
        }
      }

      @FinishBundle
      public void finishBundle(FinishBundleContext context)
          throws IOException, InterruptedException {
        if (featureRows.size() > 0) {
          try {
            executeBatch();
            featureRows.forEach(
                row ->
                    context.output(
                        successfulInsertsTag, row, Instant.now(), GlobalWindow.INSTANCE));
            featureRows.clear();
            skippedFeatureRows.forEach(
                row -> processSkippedFeatureRow(row, context.getPipelineOptions().getJobName()));
            skippedFeatureRows.clear();
          } catch (Exception e) {
            featureRows.forEach(
                failedMutation -> {
                  FailedElement failedElement =
                      toFailedElement(failedMutation, e, context.getPipelineOptions().getJobName());
                  context.output(
                      failedInsertsTupleTag, failedElement, Instant.now(), GlobalWindow.INSTANCE);
                });
            featureRows.clear();
          }
        }
      }

      @Teardown
      public void teardown() {
        redisIngestionClient.shutdown();
      }
    }
  }
}
