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
echo   RosKasa Android - Git Commit ^& Push na GitHub
echo   Repozitorij: https://github.com/ros-d-o-o/RosKasaAndroid.git
echo   Aktivna veja: master
echo ========================================================
echo.

git status -s
echo.
set /p commitMsg="Vnesite opis sprememb (ali pritisnite ENTER za privzeto): "

if "%commitMsg%"=="" (
    set commitMsg=Posodobitev projekta RosKasa
)

echo.
echo [1/3] Dodajanje sprememb (git add .)...
git add .

echo.
echo [2/3] Belezenje sprememb (git commit)...
git commit -m "%commitMsg%"

echo.
echo [3/3] Posiljanje na GitHub (git push -u origin master)...
git push -u origin master

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo   [USPEH] Spremembe so uspesno poslane na GitHub!
    echo ========================================================
) else (
    echo.
    echo ========================================================
    echo   [NAPAKA] Pri posiljanju je prislo do tezave.
    echo ========================================================
)

echo.
pause
