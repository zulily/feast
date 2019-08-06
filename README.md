## Feast Docs GitHub Pages

In order to generate documentation for Feast Python SDK:
```
cd sdk/python

# Remove old build
rm -rf docs/_build

# Generate static HTML pages from documentation source located in "docs"
# folder and publish the output in "docs/_build/html" folder
sphinx-build docs docs/_build/html
```