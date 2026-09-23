# HACO-CPS-LSR 2.0 无人值守自动训练
param(
    [int]$DeadlineHours = 72,
    [int]$SmokeSamples = 1000
)
$env:TRAINING_DEADLINE_HOURS = "$DeadlineHours"
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
python algorithm/learning/training/run_auto_training.py
exit $LASTEXITCODE
