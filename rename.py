import os

repo_dir = r"c:\Users\Pulak Mandal\PixelTuneJul"
directories_to_rename = [
    r"app\src\androidTest\java\com\theveloper\pixelplay",
    r"app\src\main\java\com\theveloper\pixelplay",
    r"app\src\test\java\com\theveloper\pixelplay",
    r"baselineprofile\src\main\java\com\theveloper\pixelplay",
    r"shared\src\main\java\com\theveloper\pixelplay",
    r"wear\src\main\java\com\theveloper\pixelplay"
]

for d in directories_to_rename:
    path = os.path.join(repo_dir, d)
    if os.path.exists(path):
        os.rename(path, os.path.join(os.path.dirname(path), "pixeltune"))
        print(f"Renamed {path}")

# Now replace in files
for root, dirs, files in os.walk(repo_dir):
    if any(x in root for x in [".git", r"\build", r"\.gradle", r"\.idea", r"\node_modules"]):
        continue
    for file in files:
        if file.endswith((".kt", ".kts", ".xml", ".pro")):
            filepath = os.path.join(root, file)
            try:
                with open(filepath, "r", encoding="utf-8") as f:
                    content = f.read()
                
                new_content = content.replace("com.theveloper.pixelplay", "com.theveloper.pixeltune")
                
                if new_content != content:
                    with open(filepath, "w", encoding="utf-8") as f:
                        f.write(new_content)
                    print(f"Updated {filepath}")
            except Exception as e:
                print(f"Error reading/writing {filepath}: {e}")
