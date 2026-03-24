$repo_dir = "c:\Users\Pulak Mandal\PixelTuneJul"
$Utf8NoBomEncoding = New-Object System.Text.UTF8Encoding $False

$msgToRestore = 'name="app_name_change_message">We have changed our app''s name from PixelPlay to PixelTune due to a trademark-related issue. Keep playing!</string>'
$msgRegex = 'name="app_name_change_message">.*?</string>'

# 1. Rename files globally
Get-ChildItem -Path $repo_dir -Recurse | Where-Object { 
    $_.FullName -notmatch "\\(\.git|build|\.gradle|\.idea|node_modules)\\" -and
    ($_.Name -match "PixelPlay" -or $_.Name -match "PixelPlayer")
} | ForEach-Object {
    if (!$_.PSIsContainer) {
        $newName = $_.Name -replace "PixelPlayer", "PixelTune" -replace "PixelPlay", "PixelTune"
        $newPath = Join-Path $_.DirectoryName $newName
        Rename-Item -Path $_.FullName -NewName $newName
        Write-Host "Renamed file: $($_.FullName) -> $newPath"
    }
}

# 2. Replace content
Get-ChildItem -Path $repo_dir -Recurse -File | Where-Object { 
    $_.FullName -notmatch "\\(\.gemini|\.git|build|\.gradle|\.idea|node_modules)\\" -and
    $_.Name -match "(?i)\.(kt|kts|xml|pro|md|conf|txt|yaml|yml|properties)$" -and
    $_.Name -notin @("rename.py", "task.md", "rename_rebrand.py", "rename_rebrand.ps1")
} | ForEach-Object {
    $content = [System.IO.File]::ReadAllText($_.FullName, $Utf8NoBomEncoding)
    if (-not [string]::IsNullOrEmpty($content)) {
        $newContent = $content -replace "PixelPlayer", "PixelTune" -replace "PixelPlay", "PixelTune"
        
        if ($_.Name -eq "strings.xml") {
            $newContent = [regex]::Replace($newContent, $msgRegex, $msgToRestore)
        }
        
        if ($newContent -cne $content) {
            [System.IO.File]::WriteAllText($_.FullName, $newContent, $Utf8NoBomEncoding)
            Write-Host "Updated content in $($_.FullName)"
        }
    }
}
