# Development Text Probe

This host-only CLI accepts `--config <path>` and `--synthetic-probe`. It submits only the fixed current input `只回复测试通过`, with generic reply instructions and empty history/context. Both the explicit flag and `allowSyntheticTextUpload=true` are required. Output contains fixed configuration/status codes; generated text, error bodies and credentials are never printed.

The Java Properties file requires `service`, `baseUrl`, `protocolId`, `model`, `apiKey`, `usableWindowTokens`, `inputBudgetTokens`, `maxOutputTokens`, `safetyTokens` and `timeoutMillis`. HTTPS is required. Positive budgets must fit the usable window. `protocolId` must be `DEEPSEEK_RESPONSES`; `protocol` is an explicit alias. `contextBudgetTokens` is an alias for `inputBudgetTokens`. When both names are present their values must match. No model-window defaults are inferred.

`allowSyntheticTextUpload` defaults to `false`; if present it must be exactly `true` or `false`. Optional `purpose` must be `DEVELOPMENT_TEST`. Keys are read only from the explicit local file, registered in memory, and never written back or accepted on the command line. This tool is not an Android application dependency.
