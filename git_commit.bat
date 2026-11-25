@echo off
:: Nastavitev Windows Credential Manager (GCM)
git config --global credential.helper manager > nul 2>&1
setlocal EnableDelayedExpansion
chcp 65001 >nul
title RosKasaAndroid - Git Commit in Push

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

:: Nastavi Git uporabnika
git config user.name "mausmb"
git config user.email "21288864+mausmb@users.noreply.github.com"
git config --global user.name "mausmb" > nul 2>&1
git config --global user.email "21288864+mausmb@users.noreply.github.com" > nul 2>&1

set CURRENT_BRANCH=
for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set CURRENT_BRANCH=%%b
if "%CURRENT_BRANCH%"=="" (
    git checkout master > nul 2>&1
    set CURRENT_BRANCH=master
)

echo ======================================================================
echo   Git Commit in Push: RosKasaAndroid
echo   Repozitorij: https://github.com/ros-d-o-o/RosKasaAndroid.git
echo   Trenutno aktivna veja: [%CURRENT_BRANCH%]
echo ======================================================================
echo.
echo Izberite moznost:
echo 1. Nadaljuj na trenutni veji [%CURRENT_BRANCH%]
echo 2. Preklopi na glavno vejo [master] in nadaljuj
echo 3. Ustvari novo vejo (nova naloga / feature branch)
echo 4. Preklici
echo.
set /p branch_choice="Izbira (1-4) [privzeto 1]: "
if "%branch_choice%"=="" set branch_choice=1

if "%branch_choice%"=="4" (
    echo Preklicano.
    pause
    exit /b
)

if "%branch_choice%"=="2" (
    echo.
    echo Preklapljam na vejo master...
    git checkout master
    if errorlevel 1 (
        echo [NAPAKA] Preklop na vejo master ni uspel!
        pause
        exit /b 1
    )
    set CURRENT_BRANCH=master
    goto :CONFIRM_BRANCH
)

if "%branch_choice%"=="3" goto :CREATE_NEW_BRANCH
goto :CONFIRM_BRANCH

:CREATE_NEW_BRANCH
echo.
echo Primeri imen vej:
echo   - feature/ui-popravki
echo   - feature/obracunani-iskanje
echo   - fix/hotkey-placila
echo.
set /p new_branch_name="Vnesite ime nove veje: "
if "%new_branch_name%"=="" (
    echo [NAPAKA] Ime veje ne sme biti prazno!
    pause
    exit /b 1
)
:: Zamenjaj presledke z vezaji
set new_branch_name=%new_branch_name: =-%

echo.
echo Ustvarjam in preklapljam na novo vejo: %new_branch_name% ...
git checkout -b "%new_branch_name%"
if errorlevel 1 (
    echo [NAPAKA] Ustvarjanje veje ni uspelo!
    pause
    exit /b 1
)
set CURRENT_BRANCH=%new_branch_name%
goto :STAGING

:CONFIRM_BRANCH
if not "%CURRENT_BRANCH%"=="master" goto :STAGING
echo.
echo ======================================================================
echo OPOZORILO: Trenutno ste na stabilni veji MASTER!
echo Neposreden push na master je zasciten ali ni priporocljiv.
echo Spremembe se v master zdruzujejo preko spleta (Pull Request).
echo ======================================================================
set /p force_master="Zelite kljub temu nadaljevati na veji master? (D/N): "
if /i not "%force_master%"=="D" (
    echo Prekinjam. Predlagamo izbiro 3 za novo vejo.
    pause
    exit /b
)

:STAGING
echo.
echo Pregled sprememb v repozitoriju (RosKasaAndroid - veja: %CURRENT_BRANCH%):
echo ----------------------------------------------------------------------
git status -s
echo ----------------------------------------------------------------------
echo.
set /p commit_msg="Vnesite opis sprememb (commit message): "
if "%commit_msg%"=="" (
    echo [NAPAKA] Sporocilo za commit ne sme biti prazno!
    pause
    exit /b 1
)

echo.
echo 1. Dodajanje sprememb (git add .)...
git add .

echo.
echo 2. Shranjevanje sprememb (git commit)...
git commit -m "%commit_msg%"
if errorlevel 1 (
    echo [OPOMBA] Ni novih sprememb ali pa so datoteke ze shranjene.
)

echo.
echo 3. Posiljanje v repozitorij RosKasaAndroid na vejo [%CURRENT_BRANCH%] ...
git push -u origin %CURRENT_BRANCH%
if not errorlevel 1 goto :PUSH_OK

echo.
echo Poskusam s prisilnim vnosom veje...
git push -u origin %CURRENT_BRANCH% --force
if not errorlevel 1 goto :PUSH_OK

echo.
echo ======================================================================
echo   [NAPAKA] Posiljanje na GitHub (RosKasaAndroid) ni uspelo!
echo   Preverite povezavo ali pravice dostopa.
echo ======================================================================
pause
exit /b 1

:PUSH_OK
echo.
echo ======================================================================
echo   USPESNO ZAKLJUCENO! Vse spremembe so v RosKasaAndroid na veji [%CURRENT_BRANCH%].
echo ======================================================================

if "%CURRENT_BRANCH%"=="master" goto :DONE
echo.
echo Ali zelite odpreti spletno stran na GitHubu za zdruzitev (Pull Request) v master?
set /p open_pr="Odpri Pull Request v brskalniku? (D/N) [privzeto D]: "
if "%open_pr%"=="" set open_pr=D
if /i "%open_pr%"=="D" (
    echo Odpiram GitHub spletno stran za zdruzitev veje %CURRENT_BRANCH% v master...
    start https://github.com/ros-d-o-o/RosKasaAndroid/compare/master...%CURRENT_BRANCH%?expand=1
)

:DONE
echo.
pause
exit /b 0
