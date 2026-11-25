@echo off
:: Nastavitev Windows Credential Manager (GCM)
git config --global credential.helper manager > nul 2>&1
setlocal EnableDelayedExpansion
chcp 65001 >nul
title RosKasaAndroid - Git Pull / Restore

set REPO_DIR=%~dp0
if "%REPO_DIR:~-1%"=="\" set REPO_DIR=%REPO_DIR:~0,-1%
cd /d "%REPO_DIR%"

:: VEDNO PREVERI IN PREKLOPI NA PRAVI REPOZITORIJ: RosKasaAndroid
git remote get-url origin > nul 2>&1
if errorlevel 1 (
    git remote add origin https://github.com/ros-d-o-o/RosKasaAndroid.git > nul 2>&1
) else (
    git remote set-url origin https://github.com/ros-d-o-o/RosKasaAndroid.git > nul 2>&1
)

set CURRENT_BRANCH=
for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set CURRENT_BRANCH=%%b
if "%CURRENT_BRANCH%"=="" (
    git checkout master > nul 2>&1
    set CURRENT_BRANCH=master
)

echo ======================================================================
echo   Git Pull / Restore: RosKasaAndroid
echo   Repozitorij: https://github.com/ros-d-o-o/RosKasaAndroid.git
echo   Trenutno aktivna veja: [%CURRENT_BRANCH%]
echo ======================================================================
echo.
echo Izberite moznost:
echo 1. Prevzemi zadnje spremembe za trenutno vejo [%CURRENT_BRANCH%]
echo 2. Preklopi na glavno vejo [master] in prevzemi (git pull origin master)
echo 3. Preklopi na drugo vejo in prevzemi
echo 4. Zavrzi lokalne spremembe in povrni repozitorij (git restore .)
echo 5. Izhod
echo.
set /p opt="Izbira (1-5) [privzeto 1]: "
if "%opt%"=="" set opt=1

if "%opt%"=="1" (
    echo.
    echo Prevzemam iz repozitorija RosKasaAndroid (veja %CURRENT_BRANCH%)...
    git pull origin %CURRENT_BRANCH% --no-rebase
    if errorlevel 1 (
        echo [NAPAKA] Pri posodobitvi veje %CURRENT_BRANCH% je prislo do tezave.
    ) else (
        echo [USPEH] Veja %CURRENT_BRANCH% je uspesno posodobljena!
    )
) else if "%opt%"=="2" (
    echo.
    echo Preklapljam na vejo master...
    git checkout master
    if errorlevel 1 (
        git fetch origin master
        git checkout -B master origin/master
    )
    echo Prevzemam zadnje spremembe iz origin/master...
    git pull origin master --no-rebase
    if errorlevel 1 (
        echo [NAPAKA] Pri posodobitvi veje master je prislo do tezave.
    ) else (
        echo [USPEH] Veja master je uspesno posodobljena!
    )
) else if "%opt%"=="3" (
    echo.
    echo Osvezujem seznam vej (git fetch)...
    git fetch --all --prune
    echo.
    echo Razpolozljive veje:
    git branch -a
    echo.
    set /p target_branch="Vnesite ime veje (brez origin/): "
    if "!target_branch!"=="" (
        echo [NAPAKA] Ime veje ne sme biti prazno!
        pause
        exit /b 1
    )
    git checkout "!target_branch!"
    if errorlevel 1 (
        echo [NAPAKA] Preklop na vejo !target_branch! ni uspel!
        pause
        exit /b 1
    )
    git pull origin "!target_branch!" --no-rebase
) else if "%opt%"=="4" (
    echo.
    echo OPOZORILO: To bo zavrglo vse lokalne neprijavljene spremembe!
    set /p confirm="Ste prepricani? (D/N): "
    if /i "!confirm!"=="D" (
        echo Obnavljam repozitorij...
        git restore .
        echo [USPEH] Lokalne spremembe so povrnjene.
    ) else (
        echo Preklicano.
    )
) else (
    echo Izhod.
)

echo.
pause
exit /b 0
