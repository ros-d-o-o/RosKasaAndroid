@echo off
cd /d "%~dp0"
:: Nastavitev Windows Credential Manager (GCM)
git config --global credential.helper manager > nul 2>&1
chcp 65001 >nul

:: VEDNO PREVERI IN PREKLOPI NA PRAVI REPOZITORIJ: RosKasaAndroid
git remote get-url origin > nul 2>&1
if errorlevel 1 (
    git remote add origin https://github.com/ros-d-o-o/RosKasaAndroid.git > nul 2>&1
) else (
    git remote set-url origin https://github.com/ros-d-o-o/RosKasaAndroid.git > nul 2>&1
)

:: VEDNO PREKLOPI NA GLAVNO VEJO: master
git checkout master > nul 2>&1
if errorlevel 1 (
    git fetch origin master
    git checkout -B master origin/master
)

echo ========================================================
echo   RosKasa Android - Git Pull (Posodobitev iz GitHub)
echo   Repozitorij: https://github.com/ros-d-o-o/RosKasaAndroid.git
echo   Aktivna veja: master
echo ========================================================
echo.

git pull origin master --no-rebase
if %ERRORLEVEL% equ 0 (
    echo.
    echo [USPEH] Repozitorij je uspesno posodobljen!
) else (
    echo.
    echo [NAPAKA] Pri posodobitvi je prislo do tezave.
)

echo.
pause
