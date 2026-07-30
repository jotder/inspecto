# ASN.1 Decoder Agents and Workflow

This document outlines the workflow of the ASN.1 decoder and the agents involved in the process. The system is designed to be highly configurable, using a set of rules and definitions to parse, decode, and transform ASN.1 data.

## Core Workflow

The ASN.1 decoding process follows these main steps:

1.  **Grammar Parsing:** The system starts by reading a custom ASN.1 grammar file (`*.asn`). From this grammar, it creates a detailed configuration for each ASN.1 tag, establishing relationships between them (e.g., parent-child, sequences, choices).

2.  **Reference Tag Mapping:** A reference map is built, where each tag is assigned a unique path (e.g., `1.2.1.0.5`). This map is crucial for looking up tag definitions during the decoding process.

3.  **Data Decoding:** The decoder reads a raw ASN.1 data file from a `data` directory. It traverses the data, and for each tag encountered, it looks up its definition in the previously generated grammar configuration.

4.  **Transformation Template Generation:** Based on the ASN.1 grammar, the system can generate a configuration template (`*.json`) for data flattening and transformation. This template defines how the decoded ASN.1 data structure should be converted into a different format.

5.  **Data Transformation:** Using the transformation template, the decoded data is transformed into the final desired format.

## Directory Structure Convention

The project follows a specific directory structure for configuration and data:

-   **`config` Directory:** Each leaf directory under `config` represents a specific data source or type. It must contain two main files:
    -   `*.asn`: A custom, parsable grammar file that defines the structure of the ASN.1 data.
    -   `*.json`: A transformation configuration file that defines how the decoded data should be formatted.

-   **`data` Directory:** Each leaf directory under `data` corresponds to a configuration directory and contains the raw ASN.1 data files to be processed.

## Agent Configuration

Global agent and process settings are configured through the `rules` file located in `config/generic`. This file defines parameters for file paths, grammar definitions, and transformation settings, while the leaf-directory files provide the specific grammar and transformation logic.
