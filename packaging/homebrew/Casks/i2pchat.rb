cask "i2pchat" do
  version "1.5.5"

  on_arm do
    sha256 "e793c4586f137775b852bbd7347dbf82009838d24dad63e0ad72be7d0ffc9742"

    url "https://github.com/MetanoicArmor/I2PChat-ng/releases/download/v#{version}/I2PChat-macOS-arm64-v#{version}.zip"

    # build-macos.sh packs with `ditto --keepParent` → zip root is I2PChat-macOS-*-bundle/
    app "I2PChat-macOS-arm64-bundle/I2PChat.app"
  end
  on_intel do
    sha256 "21599af37d8fe9025d9cce7cbfbc449f7a4be8c2097fbcac0684da1207c909d3"

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
