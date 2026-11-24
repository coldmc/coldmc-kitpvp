# KitPvP

ColdMC's own Kit PvP plugin. It replaced KitBattle on the ColdMC server and runs the arena worlds: kits and their upgrade tiers, rarities, the kit shop, soup, duels, challenges and kit abilities.

- `src/se/coldmc/kitpvp/`: the plugin source.
- `res/`: `plugin.yml`, default `config.yml` and `lang.yml`, `abilities.yml`, and `kits/Example.yml`, the template for the kit file format. The live kits are kept on the server, not here.
- `build.sh`: builds `KitPvP.jar` with JDK 25 (`--release 21`) against the server's API jars. Set `COLDMC_SERVER` to the server directory.
