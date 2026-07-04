Initial release of VoiceBridge — Fabric mod + web client.

**Installer setup** - Minecraft 1.21.1 Fabric server with Java 21+.

**Required mods** (place in `mods/` folder):
- `voicebridge-1.0.0.jar` (this mod)
- `fabric-api-0.116.13+1.21.1.jar` — Fabric API
- `simple-voice-chat-fabric-1.21.1-2.6.20.jar` — Simple Voice Chat

**Steps**:
1. Install Fabric Loader on your 1.21.1 server
2. Drop the three jars above into `mods/`
3. Start server — VoiceBridge starts WebSocket on port 8080
4. Ensure port 8080 is open in your firewall
5. In-game, run `/voicechat web` — click the link
6. Web client loads at https://vc.djcheesus.com — players see live positions

**Features**:
- Embedded WebSocket server (port 8080)
- 4-digit token auth via /voicechat web
- Real-time player position streaming
- Simple Voice Chat API plugin registered
