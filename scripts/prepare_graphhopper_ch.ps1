# prepare_graphhopper_ch.ps1 — GraphHopper CH/LM 图预处理（开源复用）
param(
    [string]$OsmFile = "tools/osm-data/chongqing-260921.osm.pbf",
    [string]$GraphDir = "tools/graphhopper/graph-cache",
    [string]$Profile = "bus",
    [string]$Jar = "tools/graphhopper/graphhopper-web.jar"
)
$ErrorActionPreference = "Continue"
if (!(Test-Path $OsmFile)) { Write-Host "OSM missing: $OsmFile"; exit 1 }
if (!(Test-Path $Jar)) {
    Write-Host "GraphHopper jar missing; LocalGraph fallback (real OSM edges) remains available."
    exit 2
}
java -Xmx2g -jar $Jar graph.action=import graph.datareader.file=$OsmFile graph.location=$GraphDir graph.profiles=$Profile
Write-Host "Prepared CH graph at $GraphDir profile=$Profile"
