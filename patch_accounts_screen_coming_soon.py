import re

file_path = 'app/src/main/java/com/theveloper/pixelplay/presentation/screens/AccountsScreen.kt'
with open(file_path, 'r') as f:
    content = f.read()

content = content.replace("val isComingSoon = account.service == ExternalServiceAccount.GOOGLE_DRIVE", "val isComingSoon = account.service == ExternalServiceAccount.GOOGLE_DRIVE || account.service == ExternalServiceAccount.YOUTUBE || account.service == ExternalServiceAccount.SOUNDCLOUD")
content = content.replace("val isComingSoon = service == ExternalServiceAccount.GOOGLE_DRIVE", "val isComingSoon = service == ExternalServiceAccount.GOOGLE_DRIVE || service == ExternalServiceAccount.YOUTUBE || service == ExternalServiceAccount.SOUNDCLOUD")

with open(file_path, 'w') as f:
    f.write(content)
