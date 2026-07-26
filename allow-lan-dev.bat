@echo off
REM ============================================================================
REM  Opens the Windows Firewall for local development so a phone or tablet on
REM  the same wifi can reach the app.
REM
REM  RUN THIS BY RIGHT-CLICKING IT AND CHOOSING "Run as administrator".
REM  Adding a firewall rule requires elevation; a normal double-click fails
REM  with "The requested operation requires elevation".
REM
REM  Two ports, both required:
REM    4200 - the Angular dev server (serves the page)
REM    8080 - the Spring Boot API (serves the data the page loads)
REM  Opening only 4200 gives you a page that renders and then fails every
REM  request, which looks like a broken app rather than a firewall problem.
REM
REM  To undo later, run: allow-lan-dev.bat /remove
REM ============================================================================

if /I "%~1"=="/remove" goto remove

REM Windows silently creates inbound BLOCK rules for node.exe and java.exe when the
REM "Allow this app to communicate on the network?" popup is dismissed or denied. In
REM Windows Firewall a Block rule ALWAYS beats an Allow rule, so the port rules added
REM below are overridden and the app stays unreachable no matter how many times they
REM are re-added. Removing the blocks first is what actually unblocks the LAN.
echo Removing any inbound BLOCK rules for node.exe / java.exe...
netsh advfirewall firewall delete rule name="Node.js JavaScript Runtime" dir=in
netsh advfirewall firewall delete rule name="java" dir=in

echo.
echo Adding firewall rules for TesseraApp local development...
netsh advfirewall firewall add rule name="TesseraApp dev 4200" dir=in action=allow protocol=TCP localport=4200
netsh advfirewall firewall add rule name="TesseraApp dev 8080" dir=in action=allow protocol=TCP localport=8080
echo.
echo ----------------------------------------------------------------
echo  Done. On your phone, open a PRIVATE / INCOGNITO tab and go to:
echo.
echo      http://192.168.1.187:4200
echo.
echo  Use a private tab so the browser does not autocomplete back to
echo  localhost, which would silently send you to the phone itself.
echo ----------------------------------------------------------------
echo.
pause
goto :eof

:remove
echo Removing the TesseraApp development firewall rules...
netsh advfirewall firewall delete rule name="TesseraApp dev 4200"
netsh advfirewall firewall delete rule name="TesseraApp dev 8080"
echo Done.
pause
