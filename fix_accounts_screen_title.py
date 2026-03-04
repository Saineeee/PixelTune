import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/presentation/screens/AccountsScreen.kt'
with open(file_path, 'r') as f:
    content = f.read()

content = content.replace('ExternalServiceAccount.SOUNDCLOUD -> "SoundCloud\\"', 'ExternalServiceAccount.SOUNDCLOUD -> "SoundCloud"')

with open(file_path, 'w') as f:
    f.write(content)
