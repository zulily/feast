# Feast Python Library

Feast (Feature Store) is a tool to manage storage and access of machine learning features. It aims to:
* Support ingesting feature data via batch or streaming
* Provide scalable storage of feature data for serving and training
* Provide an API for low latency access of features
* Enable discovery and documentation of features
* Provide an overview of the general health of features in the system

This Python library allows you to **register** features and entities in Feast, **ingest** feature values, and retrieve the values for model **training and serving**.

## Installation

Install `feast` library using `pip`:

```sh
 pip install feast
```

> Make sure you have a running Feast instance. If not, follow this [installation guide](https://github.com/gojek/feast/blob/72cc8bd2cd0040f7bc44df255f95bad00cacd720/docs/install.md)

## Configuration
All interaction with feast cluster happens via an instance of `feast.sdk.client.Client`. The client should be pointed to correct core/serving URL of the feast cluster
```python
from feast.sdk.client import Client

# Assuming you are running Feast locally so Feast hostname is localhost
FEAST_CORE_URL="localhost:8080"
FEAST_SERVING_URL="localhost:8081"

feast_client = Client(
    core_url=FEAST_CORE_URL, 
    serving_url=FEAST_SERVING_URL, 
    verbose=True)
```

## Spec Registration

Entities and features can be registered explicitly beforehand or during the data ingestion. In order to register an entity using Feast client:

```python
from feast.sdk.resources.entity import Entity

entity = Entity(
    name="word",
    description="word found in shakespearean works"
)

feast_client.apply(entity)
```

To register a feature for an entity using Feast client:

```python
from feast.sdk.resources.feature import Feature, Datastore, ValueType

word_count_feature = Feature(
    entity="word",
    name="count",
    value_type=ValueType.INT32,                      
    description="number of times the word appears",
    tags=["tag1", "tag2"],
    owner="bob@feast.com",
    uri="https://github.com/bob/example"
)

feast_client.apply(word_count_feature)
```

Read more on entity/feature fields here: [Entity Spec](https://github.com/gojek/feast/blob/72cc8bd2cd0040f7bc44df255f95bad00cacd720/docs/specs.md)


## Ingestion

Let's create a simple [pandas](https://pandas.pydata.org/) dataframe

```python
import pandas as pd

words_df = pd.DataFrame({
    "word": ["the", "and", "i", "to", "of", "a"],
    "count": [28944, 27317, 21120, 20136, 17181, 14945]
})
```

And import it into the feast store:

```python
from datetime import datetime
from feast.sdk.importer import Importer

STAGING_LOCATION = "gs://your-bucket"  

importer = Importer.from_df(words_df, 
                           entity="word", 
                           owner="bob@feast.com",
                           id_column="word",
                           timestamp_value=datetime(2018, 1, 1))
    
feast_client.run(importer)
```

This will start an import job and ingest data into both warehouse and serving feast stores.
You can also import data from a CSV file with `Importer.from_csv(...)` or  BigQuery using `Importer.from_bq(...)`

## Model Training 

Now, when you have some data in the Feast, you may want to retrieve that dataset and train your model. Creating a training dataset allows you to isolate the data that goes into the model training step, allowing for reproduction and traceability. 

You can retrieve the features required for training by specifying them in a `FeatureSet`:

```python
from feast.sdk.resources.feature_set import FeatureSet

feature_set = FeatureSet(entity="word", features=["word.count"])
dataset = feast_client.create_dataset(feature_set, 
                                      start_date="2010-01-01", 
                                      end_date="2018-01-01")
train_df = feast_client.download_dataset_to_df(dataset)

# train your model
# ...
``` 

## Model Serving

Feast provides a means for accessing stored features in a serving environment, at low latency and high load. You have to provide the ID of the entities and features to retrieve from the Serving store:

```python
feature_set = FeatureSet(entity="word", features=["word.count"])
serving_df = feast_client.get_serving_data(
  feature_set, 
  entity_keys=["you", "and", "i"]
)
```

This will return a dataframe in `serving_df` object as follows:

|     | word | count |
| --- | ---  | ---   |
| 0   | and  | 27317 |
| 1   | you  | 13989 |
| 2   | i    | 21120 |
