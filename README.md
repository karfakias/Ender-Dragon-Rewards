# Dragon Rewards

Dragon Rewards is made for SMPs where the End is limited, world borders are used, or server owners want dragon fights to stay useful after the first kill.

When the dragon dies, the mod rolls for an Elytra and a Dragon Head. Missed drops raise the next chance, so bad luck does not last forever. Rewards spawn in owner-only chests in The End and expire after the configured claim time.

Discord-MC-Chat support is optional. If DMCC is installed, reward messages are sent as embeds in the same channel DMCC already uses for Minecraft chat. Both the stable 2.x mod id (`discord-mc-chat`) and the v3 beta mod id (`discord_mc_chat`) are supported.

## Commands

- `/dragonrewards help`
- `/dragonrewards status`
- `/dragonrewards reload`
- `/dragonrewards save`
- `/dragonrewards debug <true|false>`
- `/dragonrewards feature elytra <true|false>`
- `/dragonrewards feature dragon_head <true|false>`
- `/dragonrewards chance get`
- `/dragonrewards chance reset [elytra|dragon_head]`
- `/dragonrewards chance set <elytra|dragon_head> <0..1>`
- `/dragonrewards chance add <elytra|dragon_head> <-1..1>`
- `/dragonrewards chance simulate <kills>`
- `/dragonrewards chests list`
- `/dragonrewards chests cleanup`
- `/dragonrewards chests clear`
- `/dragonrewards chests time <minutes>`
- `/dragonrewards chests delay <seconds>`
- `/dragonrewards chests remove <x> <y> <z>`
- `/dragonrewards chests spawn <player> <elytra:true|false> <dragon_head:true|false>`
- `/dragonrewards processed count`
- `/dragonrewards processed clear`
