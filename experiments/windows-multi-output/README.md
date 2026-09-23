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

## Run

List the endpoints immediately before a trial:

```powershell
dotnet run --project src/PocketDisco.WindowsMultiOutput -c Release -- --list-devices
```

Run two selected endpoints with the required five-second lead:

```powershell
dotnet run --project src/PocketDisco.WindowsMultiOutput -c Release -- `
  --devices 0,1 `
  --duration-seconds 15 `
  --start-delay-ms 5000 `
  --telemetry-file run.ndjson
```

Run a coordinator-controlled trial after starting the Phase 0 coordinator:

```powershell
$env:POCKETDISCO_COORDINATOR_TOKEN = "<coordinator-token>"
dotnet run --project src/PocketDisco.WindowsMultiOutput -c Release -- `
  --devices 0,1 `
  --duration-seconds 15 `
  --coordinator-url http://192.168.1.20:8765 `
  --coordinator-trial create
```

The command prints the trial UUID before playback. Use that UUID instead of
`create` when joining the same trial from another client. Coordinator mode waits
for both Windows outputs, takes seven clock samples, and accepts only generated
signal trials with at least five seconds remaining. HTTP is limited to loopback
and private LAN addresses. Use HTTPS for other hosts.

Remove the token from the current shell after the trial:

```powershell
Remove-Item Env:POCKETDISCO_COORDINATOR_TOKEN
```

Endpoint indexes are valid only for the current enumeration. Confirm the names
printed before playback, and cancel with Ctrl+C if they are not the intended
outputs. Existing telemetry files are never overwritten.

Endpoint names are shown only for interactive selection. Telemetry uses
`output-1` and `output-2` and does not contain Windows endpoint IDs or hardware
names.

`output_ready` means the media source opened. `playback_start_command` means the
shared timeline resume command was issued. `playback_completed` is emitted only
after both players raise `MediaEnded`. None of these events proves audible onset,
acoustic skew, or drift. Those results require isolated microphone channels.
