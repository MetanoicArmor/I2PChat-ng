cask "i2pchat-tui" do
  version "1.5.9"

  on_arm do
    sha256 "56d426da93fd9bd51afaacca4af64e5e92b941291e4876986d9ea0b02fd03a76"

    url "https://github.com/MetanoicArmor/I2PChat-ng/releases/download/v#{version}/I2PChat-macOS-arm64-tui-v#{version}.zip"
  end
  on_intel do
    sha256 "b241606368e79c727e277c13f37f31bf469d52412bf52b153aaecf068a9acaa5"

    url "https://github.com/MetanoicArmor/I2PChat-ng/releases/download/v#{version}/I2PChat-macOS-x64-tui-v#{version}.zip"
  end

  name "I2PChat TUI"
  desc "Terminal UI (Textual) for I2PChat — no PyQt GUI binary"
  homepage "https://github.com/MetanoicArmor/I2PChat-ng"

  depends_on macos: :big_sur

  binary "i2pchat-tui"
  artifact "I2PChat", target: "#{HOMEBREW_PREFIX}/opt/i2pchat-tui/I2PChat"

  caveats <<~EOS
    The launcher is copied to your PATH; the PyInstaller bundle is under
    #{HOMEBREW_PREFIX}/opt/i2pchat-tui/I2PChat
    Run: i2pchat-tui [optional profile name]
  EOS
end
