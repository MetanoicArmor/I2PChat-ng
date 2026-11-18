# Firejail profile for i2pchat
# Description: P2P chat client over the I2P network (Qt GUI)
# This file is overwritten after every install/update
# Persistent local customizations
include i2pchat.local
# Persistent global definitions
include globals.local

# AppImage extracts under /tmp; notify sound may also write a temp wav there.
ignore noexec /tmp

noblacklist ${HOME}/.config/I2PChat
noblacklist ${HOME}/.i2pchat

# Allow /bin/sh (blacklisted by disable-shell.inc)
# Needed for the AUR/deb launcher symlink path and for xdg-open (QDesktopServices).
include allow-bin-sh.inc

include disable-common.inc
include disable-devel.inc
include disable-exec.inc
include disable-interpreters.inc
include disable-programs.inc
include disable-shell.inc
include disable-xdg.inc

mkdir ${HOME}/.config/I2PChat
mkdir ${HOME}/.i2pchat
whitelist ${DOWNLOADS}
whitelist ${HOME}/.config/I2PChat
whitelist ${HOME}/.i2pchat
whitelist ${PICTURES}
whitelist /opt/i2pchat
whitelist /usr/share/i2pchat
whitelist /usr/share/pixmaps
include whitelist-common.inc
include whitelist-run-common.inc
include whitelist-runuser-common.inc
include whitelist-usr-share-common.inc
include whitelist-var-common.inc

apparmor
caps.drop all
netfilter
nodvd
nogroups
?HAS_APPIMAGE: ignore noinput
noinput
nonewprivs
noprinters
noroot
notv
nou2f
novideo
protocol unix,inet,inet6
seccomp
seccomp.block-secondary

disable-mnt
?HAS_APPIMAGE: ignore private-bin
private-bin I2PChat,i2pchat,sh,xdg-open
private-cache
?HAS_APPIMAGE: ignore private-dev
private-dev
# @network / @sound are added automatically when private-etc is set (unless net none / nosound).
private-etc @tls-ca,@x11
private-tmp

dbus-user filter
dbus-user.talk org.freedesktop.Notifications
dbus-user.talk org.freedesktop.secrets
?ALLOW_TRAY: dbus-user.talk org.kde.StatusNotifierWatcher
?ALLOW_TRAY: dbus-user.own org.kde.*
dbus-system none

restrict-namespaces
