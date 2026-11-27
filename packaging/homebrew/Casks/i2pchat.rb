cask "i2pchat" do
  version "1.7.0"

  on_arm do
    sha256 "691423aa48de57cd507ed1141a0b02720251f236307bb9b9b105e75a48ba95eb"

    url "https://github.com/MetanoicArmor/I2PChat-ng/releases/download/v#{version}/I2PChat-macOS-arm64-v#{version}.zip"

    # build-macos.sh packs with `ditto --keepParent` → zip root is I2PChat-macOS-*-bundle/
    app "I2PChat-macOS-arm64-bundle/I2PChat.app"
  end
  on_intel do
    sha256 "c3e9308daf1600b75a2d91b75572e4326f096e15f09474507f14cfa8170fe446"

    url "https://github.com/MetanoicArmor/I2PChat-ng/releases/download/v#{version}/I2PChat-macOS-x64-v#{version}.zip"

    app "I2PChat-macOS-x64-bundle/I2PChat.app"
  end

  name "I2PChat"
  desc "Experimental peer-to-peer chat client for the I2P network"
  homepage "https://github.com/MetanoicArmor/I2PChat-ng"

  depends_on macos: :big_sur

  caveats <<~EOS
    Textual TUI only: install the separate cask `i2pchat-tui`, or use I2PChat.app/Contents/MacOS/I2PChat-tui inside this bundle.
  EOS
end
