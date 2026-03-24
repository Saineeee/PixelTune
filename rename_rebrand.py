import os
import re

repo_dir = r"c:\Users\Pulak Mandal\PixelTuneJul"

# 1. Rename files globally
for root, dirs, files in os.walk(repo_dir):
    if any(x in root for x in [".git", r"\build", r"\.gradle", r"\.idea", r"\node_modules"]):
        continue
    for file in files:
        if "PixelPlay" in file or "PixelPlayer" in file:
            new_name = file.replace("PixelPlayer", "PixelTune").replace("PixelPlay", "PixelTune")
            old_path = os.path.join(root, file)
            new_path = os.path.join(root, new_name)
            os.rename(old_path, new_path)
            print(f"Renamed file: {old_path} -> {new_path}")

# 2. Replace content
msg_to_restore = r'name="app_name_change_message">We have changed our app\'s name from PixelPlay to PixelTune due to a trademark-related issue. Keep playing!</string>'

for root, dirs, files in os.walk(repo_dir):
    if any(x in root for x in [".git", r"\build", r"\.gradle", r"\.idea", r"\node_modules"]):
        continue
    for file in files:
        if file.endswith((".kt", ".kts", ".xml", ".pro", ".md", ".conf", ".txt")):
            filepath = os.path.join(root, file)
            if file in ["rename_rebrand.py", "rename.py", "task.md"]:
                continue
            
            # Don't modify files in brain directory
            if ".gemini" in filepath:
                continue

            try:
                with open(filepath, "r", encoding="utf-8") as f:
                    content = f.read()
                
                new_content = content.replace("PixelPlayer", "PixelTune")
                new_content = new_content.replace("PixelPlay", "PixelTune")
                
                # Restore the specific string if it's strings.xml
                if file == "strings.xml":
                    new_content = re.sub(
                        r'name="app_name_change_message">.*?</string>',
                        msg_to_restore,
                        new_content
                    )
                
                if new_content != content:
                    with open(filepath, "w", encoding="utf-8") as f:
                        f.write(new_content)
                    print(f"Updated content in {filepath}")
            except Exception as e:
                print(f"Error reading/writing {filepath}: {e}")
