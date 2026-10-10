[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DumpPath
)

$ErrorActionPreference = 'Stop'
$stage5Container = 'er-nl2sql-stage5-test'
$stage5Root = Split-Path -Parent $PSScriptRoot
$stage5Dump = (Resolve-Path -LiteralPath $DumpPath).Path
if ($stage5Dump.StartsWith($stage5Root + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep the production dump outside the repository.'
}
if (-not (Test-Path -LiteralPath $stage5Dump -PathType Leaf)) { throw 'Dump file required.' }

function Invoke-Stage5Docker {
    param([string[]]$Arguments)
    $stage5Output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Stage5 Docker operation failed. Preserve the dedicated container for diagnosis.' }
    $stage5Output
}

$stage5Existing = & docker ps -a --filter "name=^/$stage5Container$" --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker is unavailable.' }
if ($stage5Existing) { throw 'Dedicated stage5 container already exists; this script never resets an existing copy.' }

Invoke-Stage5Docker @('run','-d','--name',$stage5Container,
    '--label','io.eranalytics.verification=stage5','-p','127.0.0.1:15445:5432',
    '-e','POSTGRES_USER=stage5_test','-e','POSTGRES_DB=er_stage5_copy',
    '-e','POSTGRES_HOST_AUTH_METHOD=trust','postgres:17-alpine')

$stage5Ready = $false
for ($stage5Attempt = 0; $stage5Attempt -lt 30; $stage5Attempt++) {
    & docker exec $stage5Container pg_isready -U stage5_test -d er_stage5_copy *> $null
    if ($LASTEXITCODE -eq 0) { $stage5Ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $stage5Ready) { throw 'Dedicated PostgreSQL did not become ready.' }

Invoke-Stage5Docker @('exec',$stage5Container,'psql','-X','-U','stage5_test','-d','er_stage5_copy',
    '-v','ON_ERROR_STOP=1','-c',"CREATE ROLE collector LOGIN; CREATE ROLE agent_ro LOGIN;")
Invoke-Stage5Docker @('exec',$stage5Container,'createdb','-U','stage5_test','er_stage5_reference')
Invoke-Stage5Docker @('exec',$stage5Container,'psql','-X','-U','stage5_test','-d','er_stage5_copy',
    '-v','ON_ERROR_STOP=1','-c',"COMMENT ON DATABASE er_stage5_copy IS 'ER_ANALYTICS_STAGE5_COPY'; COMMENT ON DATABASE er_stage5_reference IS 'ER_ANALYTICS_STAGE5_REFERENCE';")
Invoke-Stage5Docker @('cp',$stage5Dump,"${stage5Container}:/tmp/production-v2.dump")
Invoke-Stage5Docker @('exec',$stage5Container,'pg_restore','-U','stage5_test','-d','er_stage5_copy',
    '--no-owner','--no-privileges','--exit-on-error','/tmp/production-v2.dump')

# Fail before baseline if the input is not the explicitly expected V1+V2 schema.
$stage5SchemaCheck = @'
DO $$ BEGIN
  IF current_database() <> 'er_stage5_copy' OR current_user <> 'stage5_test'
     OR (SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE') <> 7
     OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
                    AND table_name='games' AND column_name='start_dtm' AND data_type='timestamp with time zone')
     OR EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
                AND table_name='participants' AND column_name IN ('participant_id','user_id')) THEN
    RAISE EXCEPTION 'Expected an isolated, restored V1+V2 copy';
  END IF;
END $$;
SELECT 'games=' || count(*) FROM games;
SELECT 'participants=' || count(*) FROM participants;
'@
$stage5SchemaCheck | & docker exec -i $stage5Container psql -X -U stage5_test -d er_stage5_copy -v ON_ERROR_STOP=1 -At
if ($LASTEXITCODE -ne 0) { throw 'Restored schema check failed; do not baseline.' }
Write-Output 'Copy prepared on localhost:15445. Run explicit baseline 2, migrate, validate next.'
