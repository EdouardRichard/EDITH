package com.sheen.rokid.plugin

data class ConfigSchema(val version: Int) { init { require(version > 0) } }
data class PluginConfiguration(val schemaVersion: Int, val values: Map<String, String>) { init { require(schemaVersion > 0) } }
