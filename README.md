furry Meteor Client addon.

Made by olstar, wrenne, em0rave, CrisisSheep.


## Modules
 
### AntiAntiAFK
 
Prevents AFK kicking by sending a swing packet with no destination. configurable interval. Optionally equips a chestplate and/or mainhand totem, spins in place, or autologs on configurable trigger events such as player proximity or being pearled.
 
### AntiAntiSpam
 
Filters spam from chat by regex matching against common spam patterns, and checking a [github repo](https://github.com/pawbase2b2t/pawhax).
 
### AutoPawjob
 
Turn it on and see :3
 
### Banner Webhook
 
Scans loaded chunks for banners and reports them to a Discord webhook. Each message includes a rendered image of the banner and the patterns used to construct it. Maintains a persistent seen-list to avoid duplicate reports.
 
### DiagBounce
 
Lets you use Mio's ebounce without getting ratted. Configurable speed (default 110 bps). Detects failures such as being flung off the wall, getting stuck, or rubberbanding, then uses Baritone to navigate back and resume automatically. Fully AFKable. Only works on diagonal highways.

To use:
Walk into diagonal highway "crack", look in the direction you want to go, activate module. Thats it :) If you get bumped off the edge when you start bouncing, use the other side of the highway. The side you use depends on which direction/quad you are in.

Video Example:

https://github.com/user-attachments/assets/66c1ed33-626b-41f6-a7f4-ee4ae5e867de
 
### InstaPaw
 
Pulls a trapdoor the instant a target player joins the server, useful for releasing someone from a trap without a dedicated bot. Look at the trapdoor, set the target's name in config, and enable.
 
### Inv Resync
 
Fixes ghost item desync that can occur when using Baritone to mine. Sends a deliberately malformed inventory packet to the server, forcing it to respond with a full resync. The interval between resyncs is configurable. For most use cases this will cause more problems than it solves.
 
### Now Playing
 
Adds a Meteor HUD element showing your currently playing song and artist, pulled from the system media session. Windows only for now.
 
### Paw Chat
 
Attach a custom prefix and/or suffix to all outgoing chat messages. Includes a built-in antispam to prevent curtis eating your messages.
 
### PawtoAnvilRename
 
Bulk rename or clear item names. Set your desired name, optionally restrict to a specific item type, then open an anvil. Supports stacked anvils and will automatically reopen when one breaks. Optionally throws exp bottles from your inventory or hotbar to maintain the XP level required for renaming, so you don't waste bottles.
 
### PawtoDyeShulkers
 
Bulk-dyes shulker boxes. Supports manual colour selection or autodetection of the most abundant dye in your inventory. Works in both your inventory grid and on a crafting table.
 
### PawtoLogoutWhisper
 
Lets whitelisted players remotely disconnect you via whisper, useful when AFKing and you need someone to log you out. Configurable disconnect delay and optional autoreconnect disable. Command format: /w YOUR_NAME !logout (aliases: !log, !disconnect, !dc). Optionally append -r reason. The reason will be displayed to the disconnected player on the disconnect screen.
EXAMPLE: /w CurtisBayliss -r kill yourself
 
### Pearl GUI
 
A configurable radial wheel HUD for quickly sending chat messages, primarily for whispers to pearl bots. Multi-page, 8 sections per page, each with a custom label and message.
 
### Pitch40 Auto Rocket
 
Automatically fires a rocket when you drop below the pitch-40 lower bound.
 
### TailTrail
 
Adds a particle trail to your character. Configurable particle type (all vanilla particles supported), spawn rate, position offsets, and velocity.
