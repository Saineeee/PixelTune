import re

with open('app/src/main/java/com/theveloper/pixeltune/data/youtube/YouTubeRepository.kt', 'r') as f:
    content = f.read()

# Fix unused variable and string interpolation
content = content.replace("var fetchSuccess = false\n            ", "")
content = content.replace("var fetchSuccess = false\n                ", "")
content = content.replace("fetchSuccess = true\n                    ", "")
content = content.replace("fetchSuccess = true\n                        ", "")
content = content.replace("\\${i + 1}", "${i + 1}")

with open('app/src/main/java/com/theveloper/pixeltune/data/youtube/YouTubeRepository.kt', 'w') as f:
    f.write(content)
