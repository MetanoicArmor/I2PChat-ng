# Firejail profile for i2pchat-tui
# Description: P2P chat client over the I2P network (terminal UI)
# This file is overwritten after every install/update
# Persistent local customizations
include i2pchat-tui.local
# Persistent global definitions
include globals.local

noblacklist ${HOME}/.config/I2PChat
noblacklist ${HOME}/.i2pchat

# Allow /bin/sh (blacklisted by disable-shell.inc)
# The packaged launcher (`i2pchat-tui`) is a shell wrapper around `I2PChat-tui`.
include allow-bin-sh.inc

include disable-common.inc
include disable-devel.inc
include disable-exec.inc
include disable-interpreters.inc
include disable-programs.inc
include disable-shell.inc
include disable-x11.inc
include disable-xdg.inc

mkdir ${HOME}/.config/I2PChat
mkdir ${HOME}/.i2pchat
whitelist ${DOWNLOADS}
whitelist ${HOME}/.config/I2PChat
whitelist ${HOME}/.i2pchat
whitelist ${PICTURES}
whitelist /opt/i2pchat-tui
whitelist /usr/share/i2pchat-tui
whitelist /usr/share/pixmaps
include whitelist-common.inc
include whitelist-run-common.inc
include whitelist-runuser-common.inc
include whitelist-usr-share-common.inc
include whitelist-var-common.inc

apparmor
caps.drop all
ipc-namespace
netfilter
no3d
nodvd
nogroups
noinput
nonewprivs
noprinters
noroot
nosound
notv
nou2f
novideo
protocol unix,inet,inet6
seccomp
seccomp.block-secondary

disable-mnt
# Launcher is a shell script (dirname/readlink); the ELF is exec'd via absolute path under /opt.
private-bin dirname,I2PChat-tui,i2pchat-tui,readlink,sh
private-cache
private-dev
# @network is added automatically when private-etc is set (unless net none).
private-etc @tls-ca
private-tmp

dbus-user filter
dbus-user.talk org.freedesktop.secrets
dbus-system none

restrict-namespaces
