; Registers mbrowser as a web browser (per user) so Windows lists it under Settings > Apps > Default apps.
!macro customInstall
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser" "" "mbrowser"
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser\DefaultIcon" "" "$INSTDIR\${APP_EXECUTABLE_FILENAME},0"
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser\shell\open\command" "" '"$INSTDIR\${APP_EXECUTABLE_FILENAME}"'
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser\Capabilities" "ApplicationName" "mbrowser"
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser\Capabilities" "ApplicationDescription" "mbrowser: a browser that syncs to your own server"
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser\Capabilities" "ApplicationIcon" "$INSTDIR\${APP_EXECUTABLE_FILENAME},0"
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser\Capabilities\URLAssociations" "http" "mbrowserURL"
  WriteRegStr HKCU "Software\Clients\StartMenuInternet\mbrowser\Capabilities\URLAssociations" "https" "mbrowserURL"
  WriteRegStr HKCU "Software\RegisteredApplications" "mbrowser" "Software\Clients\StartMenuInternet\mbrowser\Capabilities"
  WriteRegStr HKCU "Software\Classes\mbrowserURL" "" "mbrowser URL"
  WriteRegStr HKCU "Software\Classes\mbrowserURL" "URL Protocol" ""
  WriteRegStr HKCU "Software\Classes\mbrowserURL\DefaultIcon" "" "$INSTDIR\${APP_EXECUTABLE_FILENAME},0"
  WriteRegStr HKCU "Software\Classes\mbrowserURL\shell\open\command" "" '"$INSTDIR\${APP_EXECUTABLE_FILENAME}" "%1"'
!macroend

!macro customUnInstall
  DeleteRegKey HKCU "Software\Clients\StartMenuInternet\mbrowser"
  DeleteRegValue HKCU "Software\RegisteredApplications" "mbrowser"
  DeleteRegKey HKCU "Software\Classes\mbrowserURL"
!macroend
