# PrepUpload.ps1 - Prepared for ResQMesh Android Project
$projectName = "ResQMesh_Source"
$destinationFolder = Join-Path ([Environment]::GetFolderPath("Desktop")) $projectName
$mappingFile = Join-Path $destinationFolder "File_Locations.txt"

# Define what to include and what to ignore
$includeExtensions = @("*.kt", "*.xml", "*.gradle", "*.kts", "*.properties", "AndroidManifest.xml")
$excludeFolders = @("build", ".gradle", ".idea", "androidTest", "test", "bin", "obj")

# Clean start: Remove old export folder if it exists
if (Test-Path $destinationFolder) { Remove-Item -Recurse -Force $destinationFolder }
New-Item -ItemType Directory -Path $destinationFolder | Out-Null

Write-Host "Starting file collection... destination: $destinationFolder" -ForegroundColor Cyan

# Find and copy files
$files = Get-ChildItem -Recurse -File -Include $includeExtensions | Where-Object {
    $path = $_.FullName
    $skip = $false
    foreach ($ex in $excludeFolders) { if ($path -like "*\$ex*") { $skip = $true } }
    -not $skip
}

foreach ($file in $files) {
    # Calculate relative path to maintain structure
    $relativePath = Resolve-Path -Path $file.FullName -Relative
    $targetFilePath = Join-Path $destinationFolder $relativePath
    $targetDir = Split-Path $targetFilePath

    # Create directory structure in destination
    if (-not (Test-Path $targetDir)) { New-Item -ItemType Directory -Path $targetDir -Force | Out-Null }

    # Copy the file
    Copy-Item -Path $file.FullName -Destination $targetFilePath -Force

    # Log the original location
    "FILE: $($file.Name) | ORIGINAL LOCATION: $($file.FullName)" | Out-File -Append -FilePath $mappingFile
}

Write-Host "Success! Your files are ready." -ForegroundColor Green
Write-Host "1. Go to your Desktop." -ForegroundColor Yellow
Write-Host "2. Right-click the '$projectName' folder -> Compress to ZIP file." -ForegroundColor Yellow
Write-Host "3. Upload that ZIP here." -ForegroundColor Yellow