cask "i2pchat-tui" do
  version "1.7.0"

  on_arm do
    sha256 "c8fb2a7c7d5bbd2f90090974a607d1654fa14777920f530cf98f51a51724e9b2"

    url "https://github.com/MetanoicArmor/I2PChat-ng/releases/download/v#{version}/I2PChat-macOS-arm64-tui-v#{version}.zip"
  end
  on_intel do
    sha256 "e8c30f66968a6d721f28dae239defff4c19392fb65e4b5eb7c9ee814af20c050"

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
