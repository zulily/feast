/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2020 The Feast Authors
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
package feast.core.job.direct;

import feast.core.job.option.RunnerConfig;
import feast.proto.core.RunnerProto.DirectRunnerConfigOptions;

public class DirectRunnerConfig extends RunnerConfig {
  /**
   * Controls the amount of target parallelism the DirectRunner will use. Defaults to the greater of
   * the number of available processors and 3. Must be a value greater than zero.
   */
  public Integer targetParallelism;

  /* BigQuery table specification, e.g. PROJECT_ID:DATASET_ID.PROJECT_ID */
  public String deadletterTableSpec;

  /* Set the Redis key TTL based on the max age of the FeatureSet that data is being written for. */
  public boolean enableRedisTTL = false;

  /* Set the maximum amount of jitter in seconds added to Redis TTL (if TTL is enabled).  Set to 0 to disable jitter. */
  public int maxRedisTTLJitterSeconds = 0;

  public DirectRunnerConfig(DirectRunnerConfigOptions runnerConfigOptions) {
    this.deadletterTableSpec = runnerConfigOptions.getDeadLetterTableSpec();
    this.targetParallelism = runnerConfigOptions.getTargetParallelism();
    this.enableRedisTTL = runnerConfigOptions.getEnableRedisTTL();
    this.maxRedisTTLJitterSeconds = runnerConfigOptions.getMaxRedisTTLJitterSeconds();
  }
}
