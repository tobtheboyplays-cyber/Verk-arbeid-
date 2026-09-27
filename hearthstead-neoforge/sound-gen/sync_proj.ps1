# Mirror the shared tree into the sound lane's private project copy before a build.
$S='C:\Users\tobia\Hearthstead-Claude\Verk-arbeid-\hearthstead-neoforge'
$D='C:\Users\tobia\Hearthstead-Claude\sound-gen-work\proj\hearthstead-neoforge'
robocopy "$S\src" "$D\src" /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
robocopy "$S\gradle" "$D\gradle" /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if (Test-Path "$S\.toolchains") { robocopy "$S\.toolchains" "$D\.toolchains" /E /NFL /NDL /NJH /NJS /NP | Out-Null }
foreach ($f in 'build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat') { Copy-Item "$S\$f" "$D\$f" -Force }
"synced"
exit 0
