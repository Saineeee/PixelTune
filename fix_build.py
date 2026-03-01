with open("app/build.gradle.kts", "r") as f:
    content = f.read()

dep = '    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.6")\n'
if dep not in content:
    content = content.replace('    implementation(libs.tdlib)\n', '    implementation(libs.tdlib)\n' + dep)

with open("app/build.gradle.kts", "w") as f:
    f.write(content)
