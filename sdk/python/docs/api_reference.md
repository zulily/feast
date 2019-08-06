# API Reference

.. currentmodule:: feast.sdk

The main concepts with Feast API are:
- **Client** is the interface to Feast. The client methods are used to establish
  connection to Feast in order to register entities, features and to start
  an import job to ingest feature values to Feast.
- **Importer** is used to create an import configuration for ingesting feature
  values to Feast.

## Client

.. autosummary::
   :toctree: _autosummary

   client.Client

## Import Job

.. autosummary::
   :toctree: _autosummary
   
   importer.Importer

## Utilities

.. autosummary::
   :toctree: _autosummary
   
   utils.bq_util
   utils.gs_utils
   utils.print_utils
   utils.types
   
## Resources

.. autosummary::
   :toctree: _autosummary
   
   resources.entity.Entity
   resources.feature.Feature
   resources.feature_group.FeatureGroup
   resources.feature_set.FeatureSet
   resources.feature_set.FileType
   resources.feature_set.DatasetInfo
   resources.storage.Storage