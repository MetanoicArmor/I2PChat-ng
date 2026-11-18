# Firejail profile for I2PChat (PyQt6 GUI)
# Description: Peer-to-peer chat client for the I2P network (GUI)
# This file is overwritten after every install/update
#
# Covers:
#   - .deb / AUR:  /usr/bin/i2pchat            (python entry point or symlink)
#   - AppImage:    /opt/i2pchat/I2PChat.AppImage  (symlinked as /usr/bin/i2pchat)
#
# Network model (informational; firejail has no per-port filter without a
# private netns, so ports are documented here, not enforced):
#   - SAM bridge (system router): 127.0.0.1:7656
#   - SAM bridge (bundled i2pd):  127.0.0.1:17656
#   - bundled i2pd HTTP/SOCKS proxy: 127.0.0.1:14444 / 127.0.0.1:14447
#   - bundled i2pd web console:      127.0.0.1:17070
#   - bundled i2pd outbound/inbound I2P transport traffic (internet)
#
# Persistent local customizations
include i2pchat.local
# Persistent global definitions
include globals.local

# i2pchat data lives in ~/.i2pchat (profiles, downloads, images, router prefs,
# bundled i2pd datadir).
noblacklist ${HOME}/.i2pchat
# Optional: local BlindBox server example (Help -> BlindBox).
#noblacklist ${HOME}/.i2pchat-blindbox
# Optional: custom download dirs configured in the UI.
#noblacklist ${HOME}/Downloads
#noblacklist ${HOME}/Pictures

# Python is required for the .deb/AUR (non-AppImage) launch path and AppImage
# extract-and-run may spawn helpers, so disable-devel.inc and
# disable-interpreters.inc are intentionally NOT included. disable-exec.inc is
# not included either: `APPIMAGE_EXTRACT_AND_RUN=1` needs to exec from /tmp.
include disable-common.inc
include disable-passwdmgr.inc
include disable-programs.local
include disable-programs.inc
include disable-xdg.inc

# Persistent data (whitelisted, everything else in $HOME is hidden).
mkdir ${HOME}/.i2pchat
whitelist ${HOME}/.i2pchat

# Qt / fonts / keyring file fallback (python-keyring when no Secret Service).
whitelist ${HOME}/.config/fontconfig
read-only ${HOME}/.config/fontconfig
whitelist ${HOME}/.fonts
read-only ${HOME}/.fonts
whitelist ${HOME}/.local/share/fonts
read-only ${HOME}/.local/share/fonts
whitelist ${HOME}/.config/Trolltech.conf
read-only ${HOME}/.config/Trolltech.conf
whitelist ${HOME}/.config/qt6ct
read-only ${HOME}/.config/qt6ct
whitelist ${HOME}/.config/kdeglobals
read-only ${HOME}/.config/kdeglobals
whitelist ${HOME}/.local/share/python_keyring
include whitelist-common.inc
include whitelist-runuser-common.inc
include whitelist-var-common.inc

# Installed application payload (AppImage, portable layout with vendor/i2pd).
# Not hidden by the profile; keep it read-only so a compromised process cannot
# replace the AppImage or the bundled i2pd binary.
read-only /opt/i2pchat

# Ephemeral media (ephemeral_root() -> /dev/shm/i2pchat-<uid>/). /dev/shm is
# kept by private-dev below, so no extra rule is needed for the
# /dev/shm/i2pchat-* directories.

# Executing the bundled i2pd:
#   - portable/deb layout: /opt/i2pchat/vendor/i2pd/<os-arch>/i2pd
#   - AppImage: unpacked under /tmp/.mount_* (or /tmp/appimage_extracted_*)
# Neither location is noexec here. Only $HOME and the runtime dir are
# hardened.
noexec ${HOME}
noexec ${RUNUSER}

apparmor
caps.drop all
ipc-namespace
nodvd
nogroups
nonewprivs
noroot
notv
nou2f
novideo
# GUI needs unix sockets (X11, Wayland, D-Bus), inet/inet6 (SAM, i2pd) and
# netlink (Qt network interface enumeration).
protocol unix,inet,inet6,netlink
seccomp
seccomp.block-secondary
restrict-namespaces
shell none
disable-mnt

private-cache
private-dev
private-tmp

# Desktop session bus: filter. Allowed: Secret Service / KWallet (keyring),
# notifications, tray icon, XDG portals (file chooser, open URI).
dbus-user filter
dbus-user.talk org.freedesktop.secrets
dbus-user.talk org.kde.kwalletd5
dbus-user.talk org.kde.kwalletd6
dbus-user.talk org.freedesktop.Notifications
dbus-user.talk org.kde.StatusNotifierWatcher
dbus-user.talk org.freedesktop.portal.*
dbus-system none

# X11/Wayland/DRI: no `x11 none`, no `no3d` (Qt xcb + GL, or Wayland).
# For a Wayland-only session add to i2pchat.local:
#   x11 none
# and start the app with QT_QPA_PLATFORM=wayland.
