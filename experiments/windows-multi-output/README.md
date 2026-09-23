# PocketDisco Windows multi-output experiment

This command-line experiment measures Windows render endpoint behavior before a
product shell is selected. It generates its own click signal and does not capture
provider audio.

## Requirements

- Windows 11
- .NET SDK 10.0.401 or a compatible patch
- Two active render endpoints for the application fanout trial
- Isolated acoustic capture for a measured result

## Build

```powershell
dotnet restore PocketDisco.WindowsMultiOutput.sln
dotnet build PocketDisco.WindowsMultiOutput.sln -c Release --no-restore
dotnet test PocketDisco.WindowsMultiOutput.sln -c Release --no-build
```

Run `--help` for probe commands. Endpoint names are shown only for interactive
selection. Telemetry must use run-local labels and must not contain Windows
endpoint IDs or hardware names.
