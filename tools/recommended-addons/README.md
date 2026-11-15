# Recommended addons pack

The app's "Also install recommended addons" option downloads `recommended-addons.zip` from the
`recommended-addons` release (see `AddonInstaller.RECOMMENDED_URL`). The zip holds the addon
folders and `!ThorRecommended`, which fills in each addon's recommended settings in game wherever
a player has none yet (and sets up the chat window once per character).

- `!ThorRecommended/Core.lua`: the addon; `Data.lua` is generated.
- `make_data.py`: builds `Data.lua` from a player's SavedVariables, keeping options only, and
  refuses to write if any of the given names (account, characters, realms) appear in it:

      python make_data.py <SavedVariables dir> <character SavedVariables dir> <chat-cache.txt> \
          "!ThorRecommended/Data.lua" ACCOUNT CHARACTER... REALM...

- `wowsv.py`: reads and writes WoW SavedVariables files.

To update the pack, zip the addon folders and `!ThorRecommended` (top-level folders in the zip),
then replace the asset: `gh release upload recommended-addons recommended-addons.zip --clobber`.
