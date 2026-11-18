# Firejail profile for I2PChat TUI (Textual terminal frontend)
# Description: Peer-to-peer chat client for the I2P network (terminal)
# This file is overwritten after every install/update
#
# Covers /usr/bin/i2pchat-tui from:
#   - .deb (i2pchat-tui):   /usr/bin/i2pchat-tui -> /opt/i2pchat-tui/i2pchat-tui
#   - AUR i2pchat-tui-bin:  same layout
#   - AUR i2pchat-tui-ng-git / Debian source package: python launcher
#
# Network model (informational; not enforced per port by firejail):
#   - SAM bridge (system router): 127.0.0.1:7656
#   - SAM bridge (bundled i2pd):  127.0.0.1:17656
#   - bundled i2pd HTTP/SOCKS proxy: 127.0.0.1:14444 / 127.0.0.1:14447
#   - bundled i2pd web console:      127.0.0.1:17070
#   - bundled i2pd I2P transport traffic (internet)
#
# Persistent local customizations
include i2pchat-tui.local
# Persistent global definitions
include globals.local

noblacklist ${HOME}/.i2pchat
# Optional: custom download dirs configured in the UI.
#noblacklist ${HOME}/Downloads

# Python launcher path needs interpreters; disable-devel.inc and
# disable-interpreters.inc are intentionally NOT included. disable-exec.inc is
# omitted so the bundled i2pd under /opt/i2pchat-tui (or /opt/i2pchat) can run.
include disable-common.inc
include disable-passwdmgr.inc
include disable-programs.local
include disable-programs.inc
include disable-xdg.inc

mkdir ${HOME}/.i2pchat
whitelist ${HOME}/.i2pchat
# python-keyring file fallback when no Secret Service is available.
whitelist ${HOME}/.local/share/python_keyring
include whitelist-common.inc
include whitelist-runuser-common.inc
include whitelist-var-common.inc

# Installed payload (portable TUI zip layout with vendor/i2pd, or the GUI
# layout). Read-only so the bundled i2pd binary cannot be replaced.
read-only /opt/i2pchat-tui
read-only /opt/i2pchat

# Ephemeral media (/dev/shm/i2pchat-<uid>/) works because private-dev keeps
# /dev/shm.

noexec ${HOME}
noexec ${RUNUSER}

apparmor
caps.drop all
ipc-namespace
nodvd
nogroups
nonewprivs
noroot
nosound
notv
nou2f
novideo
no3d
# Terminal app: no display server. Clipboard helpers (xclip/xsel/wl-copy used
# by pyperclip) will not work under `x11 none`; to enable them put
#   ignore x11 none
# into i2pchat-tui.local.
x11 none
protocol unix,inet,inet6,netlink
seccomp
seccomp.block-secondary
restrict-namespaces
disable-mnt

private-cache
private-dev
private-tmp

# Secret Service / KWallet for python-keyring only.
dbus-user filter
dbus-user.talk org.freedesktop.secrets
dbus-user.talk org.kde.kwalletd5
dbus-user.talk org.kde.kwalletd6
dbus-system none
