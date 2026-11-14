-- WoW Mobile Link: draws a row of coloured cells at the very bottom of the screen
-- carrying unit info (name, class, health) for the WoW Mobile app's Target page.
-- The app renders the game a few pixels taller than the display, so the row is
-- never visible. It is only drawn while the app's Target page is open: the app
-- presses Ctrl+Alt+Shift+L (closed) and, to open, Ctrl+Alt+Shift+ K, M, N or B, which
-- also say what the Nearby list includes (see OPEN_KEYS).
--
-- Wire format (read by the app, see WowMobileLinkReader.kt):
--   CELLS cells across the full screen width, in a band BAND (fraction of the
--   screen height) tall at the bottom. Each cell holds 3 bits: red=1 green=2 blue=4,
--   the first bit of the three being the most significant.
--   Bits come MSB-first from this byte stream:
--     'W' 'M' version seq mode slotCount recordCount
--     records: slot class hp flags x y num nameLen name...
--       x, y: where the unit's nameplate is on screen, 1-255 left/top to right/bottom (0 = none);
--       the app clicks there to target exactly that unit (several mobs can share a name)
--       num: 1-127 for nearby units (also shown on their plates; 0 for party/arena/BG slots),
--       +128 when a tap must click the plate rather than target by name (name shared with
--       another plate, or with your target when the target isn't this plate)
--     CRC-16/CCITT (init 0xFFFF, poly 0x1021) over everything before it, high byte first.
--   mode: 0 world, 1 dungeon, 2 raid, 3 battleground, 4 arena; +128 when the cursor is
--         over a UI frame rather than the game world (the app then targets by key
--         instead of clicking a nameplate that's under, say, the quest tracker)
--   slots: 0 player, 1-4 party1-4, 10-19 battleground team, 20-29 battleground
--          enemies, 30-34 arena1-5, 40-49 nearby enemies (from on-screen nameplates: hostile
--          and neutral NPCs and enemy players), 50-59 nearby friendly NPCs, 60-69 nearby
--          friendlies (friendly players and/or NPCs, as the app asks for), 70-79 nearby
--          enemies 11-20, 80-89 nearby friendlies 11-20, 90-94 battleground team 11-15,
--          95-99 battleground enemies 11-15, 100-124 raid1-25 (outside battlegrounds;
--          num is the raid group, 1-8), 125 focus (always sent, so it can be targeted
--          without its nameplate), 129 your target (so the app knows there is one; no key)
--   class: 1 warrior 2 paladin 3 hunter 4 rogue 5 priest 6 death knight 7 shaman
--          8 mage 9 warlock 10 druid (0 unknown)
--   hp: 0-100 percent, 255 unknown.  flags: 1 dead, 2 not visible/offline, 4 your target,
--          8 hostile, 16 neutral, 32 friendly, 64 player (not an NPC),
--          128 no target key yet (appeared during combat: the app types /target instead)
--
-- Targeting: every slot has a hidden secure button and a key combination (see KEYS);
-- the app presses the key when its box is tapped. Party/arena slots target by unit,
-- battleground/nearby slots by name. Secure buttons can't be changed in combat, so
-- name slots keep their names while in combat; new names get flag 128.

local CELLS, BAND, VERSION = 320, 0.004, 3
local MAX_BYTES = math.floor(CELLS * 3 / 8)
local INTERVAL = 0.1
-- How often plate numbers are kept up while the Target page is closed.
local NUMBER_INTERVAL = 0.25

local CLASS = { WARRIOR=1, PALADIN=2, HUNTER=3, ROGUE=4, PRIEST=5, DEATHKNIGHT=6,
                SHAMAN=7, MAGE=8, WARLOCK=9, DRUID=10 }
local MODE = { none=0, party=1, raid=2, pvp=3, arena=4 }

local active, seq, cursor = false, 0, 0
local strip = CreateFrame("Frame", "WoWMobileLinkStrip")
strip:SetFrameStrata("TOOLTIP")
strip:SetFrameLevel(1000)
strip:EnableMouse(false)
strip:Hide()
local cells, cellValue = {}, {}
for i = 1, CELLS do
    local t = strip:CreateTexture(nil, "OVERLAY")
    t:SetBlendMode("DISABLE")
    cells[i] = t
end

local function layout()
    -- Unparented so Alt+Z (hide UI) doesn't hide it; same units as UIParent.
    strip:SetScale(UIParent:GetEffectiveScale())
    local w, h = UIParent:GetWidth(), UIParent:GetHeight()
    local band = h * BAND
    strip:ClearAllPoints()
    strip:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", 0, 0)
    strip:SetWidth(w)
    strip:SetHeight(band)
    for i, t in ipairs(cells) do
        t:ClearAllPoints()
        t:SetPoint("BOTTOMLEFT", strip, "BOTTOMLEFT", (i - 1) * w / CELLS, 0)
        t:SetWidth(w / CELLS)
        t:SetHeight(band)
    end
end

local function crc16(bytes, n)
    local crc = 0xFFFF
    for i = 1, n do
        crc = bit.bxor(crc, bit.lshift(bytes[i], 8))
        for _ = 1, 8 do
            if bit.band(crc, 0x8000) ~= 0 then
                crc = bit.band(bit.bxor(bit.lshift(crc, 1), 0x1021), 0xFFFF)
            else
                crc = bit.band(bit.lshift(crc, 1), 0xFFFF)
            end
        end
    end
    return crc
end

local function unitRecord(slot, unit, name)
    local _, token = UnitClass(unit)
    local hp, flags = 255, 0
    if UnitExists(unit) then
        local max = UnitHealthMax(unit)
        if max and max > 0 then hp = math.floor(UnitHealth(unit) / max * 100 + 0.5) end
        if UnitIsDeadOrGhost(unit) then flags = flags + 1 end
        if not UnitIsConnected(unit) then flags = flags + 2 end
        if UnitIsUnit(unit, "target") then flags = flags + 4 end
        if UnitIsPlayer(unit) then flags = flags + 64 end
        if UnitIsEnemy("player", unit) then flags = flags + 8
        elseif UnitReaction(unit, "player") == 4 then flags = flags + 16
        else flags = flags + 32 end
    end
    return { slot = slot, class = CLASS[token or ""] or 0, hp = hp, flags = flags, name = name or UnitName(unit) or "" }
end

-- Enemy players the client can see right now, by name -> unit token.
local function visibleEnemies()
    local seen = {}
    local function look(unit)
        if UnitExists(unit) and UnitIsPlayer(unit) and UnitIsEnemy("player", unit) then
            local name = UnitName(unit)
            if name and not seen[name] then seen[name] = unit end
        end
    end
    look("target"); look("focus"); look("mouseover"); look("targettarget")
    for i = 1, GetNumRaidMembers() do look("raid" .. i .. "target") end
    for i = 1, GetNumPartyMembers() do look("party" .. i .. "target") end
    return seen
end

-- Nameplates: WoW 3.3.5 has no "units near me" API, but nameplates exist only for
-- units within ~40 yards. Plate frames are unnamed children of WorldFrame whose first
-- child is the health StatusBar and which carry a name FontString. (Recognised by that
-- shape, not by the border texture, which nameplate addons like TidyPlates remove.)
local plates, worldChildren = {}, 0

-- A plate's FontStrings, collected once per plate frame (its regions don't change), so
-- the per-update work doesn't build tables (garbage is costly in combat).
local function plateTexts(f)
    local texts = f.wmlTexts
    if not texts then
        texts = {}
        for _, r in ipairs({ f:GetRegions() }) do
            if r.GetObjectType and r:GetObjectType() == "FontString" then texts[#texts + 1] = r end
        end
        f.wmlTexts = texts
    end
    return texts
end

-- The plate's name and the FontString it came from, and its level text ("12", "??"),
-- which helps tell apart units that share a name.
local function plateNameLevel(f)
    local name, region, level
    for _, r in ipairs(plateTexts(f)) do
        if r ~= f.wmlNum then
            local text = r:GetText()
            if text and text ~= "" then
                if text:match("^[%d%?%+]+$") then
                    level = level or text
                elseif not name then
                    -- The first non-level FontString is the name.
                    name, region = text, r
                end
            end
        end
    end
    return name, region, level
end

-- Units sharing a name get a number, "(1)", "(2)"..., shown left of the name on their
-- plate and on the app's box. A plate keeps its number while shown with the same name.
-- WoW 3.3.5 plates carry no unit id and are reused, so when a plate goes away (camera
-- turned, out of range) its number is reserved for NUM_HOLD seconds (heldNum); a plate
-- with that name showing up in that time gets it back. Dead units (health 0) give theirs
-- up at once.
local plateNum, heldNum, NUM_HOLD = {}, {}, 20

local function showNumber(f, nameRegion, num)
    if num == 0 then
        if f.wmlNum then f.wmlNum:Hide() end
        return
    end
    -- NotPlater hides the plate's own name and shows its copy in f.nameText.
    local anchor = (f.nameText and f.nameText.IsShown and f.nameText:IsShown() and f.nameText) or nameRegion
    local label = f.wmlNum
    if not label then
        label = f:CreateFontString(nil, "OVERLAY")
        label:SetTextColor(1, 0.82, 0)
        f.wmlNum = label
    end
    if label.anchor ~= anchor then
        label.anchor = anchor
        label:ClearAllPoints()
        label:SetPoint("RIGHT", anchor, "LEFT", -2, 0)
    end
    -- Only touch the label when something changed.
    local font, size, outline = anchor:GetFont()
    if font and (font ~= label.font or size ~= label.size or outline ~= label.outline) then
        label.font, label.size, label.outline = font, size, outline
        label:SetFont(font, size, outline)
    end
    if label.num ~= num then
        label.num = num
        label:SetText("(" .. num .. ")")
    end
    if not label:IsShown() then label:Show() end
end


local function isPlate(f)
    if f:GetName() then return false end
    local bar = f:GetChildren()
    if not (bar and bar.GetObjectType and bar:GetObjectType() == "StatusBar") then return false end
    for _, r in ipairs({ f:GetRegions() }) do
        if r.GetObjectType and r:GetObjectType() == "FontString" then return true end
    end
    return false
end

local function scanPlates()
    local n = WorldFrame:GetNumChildren()
    if n == worldChildren then return end
    worldChildren = n
    for _, f in ipairs({ WorldFrame:GetChildren() }) do
        if not plates[f] and isPlate(f) then plates[f] = true end
    end
end

local REACTION = { hostile = 8, neutral = 16, friendly = 32 }
local CLASS_RGB = {
    { 1, 0.78, 0.61, 0.43 }, { 2, 0.96, 0.55, 0.73 }, { 3, 0.67, 0.83, 0.45 }, { 4, 1.00, 0.96, 0.41 },
    { 5, 1.00, 1.00, 1.00 }, { 6, 0.77, 0.12, 0.23 }, { 7, 0.00, 0.44, 0.87 }, { 8, 0.41, 0.80, 0.94 },
    { 9, 0.58, 0.51, 0.79 }, { 10, 1.00, 0.49, 0.04 },
}
local function plateColour(r, g, b)
    for _, c in ipairs(CLASS_RGB) do
        -- Class-coloured plates are enemy players.
        if math.abs(r - c[2]) < 0.06 and math.abs(g - c[3]) < 0.06 and math.abs(b - c[4]) < 0.06 then return c[1], REACTION.hostile + 64 end
    end
    -- Blue plates are friendly players.
    if r < 0.2 and g < 0.2 and b > 0.8 then return 0, REACTION.friendly + 64 end
    if r > 0.8 and g < 0.3 and b < 0.3 then return 0, REACTION.hostile end
    if r > 0.8 and g > 0.8 and b < 0.3 then return 0, REACTION.neutral end
    return 0, REACTION.friendly
end

-- Each nearby group (see nearby()) remembers, per plate, the slot (1-10) it is shown in and
-- the name it had then. A plate keeps its slot while it stays shown with the same name:
-- re-sorting every packet moved units between slots, and the app showed them twice until
-- the old slot went stale. held: slots whose unit's plate went away (name and when); the
-- slot stays theirs for HOLD seconds. lastRecord: what each slot last showed.
local HOLD = NUM_HOLD

-- Player classes by name, for plates that don't show it (friendly players' plates are
-- plain blue). Learned from party/raid, target, mouseover and the combat log.
local classByName = {}
local function learnUnit(unit)
    if UnitExists(unit) and UnitIsPlayer(unit) then
        local name = UnitName(unit)
        local _, token = UnitClass(unit)
        if name and token and CLASS[token] then classByName[name] = CLASS[token] end
    end
end
local learner = CreateFrame("Frame")
learner:RegisterEvent("UPDATE_MOUSEOVER_UNIT")
learner:RegisterEvent("PLAYER_TARGET_CHANGED")
learner:RegisterEvent("PARTY_MEMBERS_CHANGED")
learner:RegisterEvent("RAID_ROSTER_UPDATE")
learner:RegisterEvent("COMBAT_LOG_EVENT_UNFILTERED")
local function learnGuid(guid, name)
    if not guid or not name or classByName[name] then return end
    -- Player GUIDs start with 0x0 on 3.3.5 (NPCs 0xF13..., pets 0xF14...).
    if guid:sub(1, 3) ~= "0x0" then return end
    local _, token = GetPlayerInfoByGUID(guid)
    if token and CLASS[token] then classByName[strsplit("-", name)] = CLASS[token] end
end
learner:SetScript("OnEvent", function(_, event, ...)
    if event == "COMBAT_LOG_EVENT_UNFILTERED" then
        local _, _, srcGuid, srcName, _, dstGuid, dstName = ...
        learnGuid(srcGuid, srcName)
        learnGuid(dstGuid, dstName)
    elseif event == "UPDATE_MOUSEOVER_UNIT" then
        learnUnit("mouseover")
    elseif event == "PLAYER_TARGET_CHANGED" then
        learnUnit("target")
    else
        for i = 1, 4 do learnUnit("party" .. i) end
        for i = 1, GetNumRaidMembers() do learnUnit("raid" .. i) end
    end
end)

local function platesShown()
    scanPlates()
    local found = {}
    local targetName = UnitExists("target") and UnitName("target")
    for f in pairs(plates) do
        if f:IsShown() then
            local bar = f:GetChildren()
            local name, nameRegion, level = plateNameLevel(f)
            if bar and bar.GetValue and name then
                local lo, hi = bar:GetMinMaxValues()
                local hp = hi and hi > lo and math.floor((bar:GetValue() - lo) / (hi - lo) * 100 + 0.5) or 255
                local cls, flags = plateColour(bar:GetStatusBarColor())
                if cls == 0 and bit.band(flags, 64) ~= 0 then cls = classByName[name] or 0 end
                -- NPCs: send the colour NotPlater draws (it recolours its own bar for threat):
                -- 128 + 2 bits each of red, green, blue.
                local shown = bar.healthBar
                if cls == 0 and bit.band(flags, 64) == 0 and shown and shown.GetStatusBarColor then
                    local r, g, b = shown:GetStatusBarColor()
                    if r then
                        local q = function(v) return math.max(0, math.min(3, math.floor(v * 3 + 0.5))) end
                        cls = 128 + q(r) * 16 + q(g) * 4 + q(b)
                    end
                end
                if targetName == name and f:GetAlpha() > 0.95 then flags = flags + 4 end
                local cx, cy = bar:GetCenter()
                local scale = bar:GetEffectiveScale() / UIParent:GetEffectiveScale()
                local px, py = 0, 0
                if cx and cy then
                    px = math.max(1, math.min(255, math.floor(cx * scale / UIParent:GetWidth() * 255 + 0.5)))
                    py = math.max(1, math.min(255, math.floor((1 - cy * scale / UIParent:GetHeight()) * 255 + 0.5)))
                end
                found[#found + 1] = { frame = f, nameRegion = nameRegion, level = level, name = name, hp = hp, class = cls, flags = flags, y = f:GetBottom() or 0, px = px, py = py }
            elseif f.wmlNum then
                f.wmlNum:Hide()
            end
        end
    end
    -- Number units that share a name (see plateNum).
    local now = GetTime()
    local keep, taken = {}, {}
    local function take(name, n)
        taken[name] = taken[name] or {}
        taken[name][n] = true
    end
    for _, r in ipairs(found) do
        local p = plateNum[r.frame]
        if p and p.name == r.name and not (taken[r.name] and taken[r.name][p.n]) then
            p.level, p.hp = r.level, r.hp
            keep[r.frame] = p
            take(r.name, p.n)
        end
    end
    -- Plates gone since last time: the number stays reserved for that unit (see heldNum),
    -- unless it died (health 0).
    for f, p in pairs(plateNum) do
        if not keep[f] and p.hp ~= 0 then
            heldNum[p.name] = heldNum[p.name] or {}
            heldNum[p.name][p.n] = { since = now, level = p.level, hp = p.hp }
        end
    end
    for name, list in pairs(heldNum) do
        for n, h in pairs(list) do
            if now - h.since > NUM_HOLD or (taken[name] and taken[name][n]) then list[n] = nil end
        end
        if not next(list) then heldNum[name] = nil end
    end
    -- New plates, nearest first (the closest of a new group tends to be (1)): take back a
    -- reserved number for that name if there is one (same level first, then nearest
    -- health), else the lowest number neither shown nor reserved.
    table.sort(found, function(a, b) return a.y < b.y end)
    for _, r in ipairs(found) do
        if not keep[r.frame] then
            local list, n = heldNum[r.name], nil
            if list then
                local bestScore
                for hn, h in pairs(list) do
                    local score = (h.level == r.level and 0 or 1000) + math.abs((h.hp or 0) - r.hp)
                    if not bestScore or score < bestScore then n, bestScore = hn, score end
                end
                if n then list[n] = nil end
            end
            if not n then
                n = 1
                while (taken[r.name] and taken[r.name][n]) or (list and list[n]) do n = n + 1 end
            end
            take(r.name, n)
            keep[r.frame] = { name = r.name, n = n, level = r.level, hp = r.hp }
        end
        r.n = keep[r.frame].n
    end
    plateNum = keep
    -- Hostile and neutral NPCs show their number, starting at (1); players and friendly
    -- NPCs don't (their names are unique, or nearly).
    for _, r in ipairs(found) do
        local plain = bit.band(r.flags, 32) ~= 0 or bit.band(r.flags, 64) ~= 0
        r.num = plain and 0 or math.min(r.n, 127)
        showNumber(r.frame, r.nameRegion, r.num)
    end
    -- +128: tap must click this plate, not target by name. Its name is shared with
    -- another plate, or with your target when that isn't this plate (say, a mob of the
    -- same name behind you): /target Name would keep that target.
    local count = {}
    for _, r in ipairs(found) do count[r.name] = (count[r.name] or 0) + 1 end
    for _, r in ipairs(found) do
        if count[r.name] > 1 or (r.name == targetName and bit.band(r.flags, 4) == 0) then
            r.num = r.num + 128
        end
    end
    return found, targetName
end

-- Puts one group's plates into its slots (index 1-size, default 10), keeping each unit in its slot.
local function slotGroup(g, found, targetName, targetShown, size)
    size = size or 10
    local plateSlot, held, lastRecord = g.plateSlot, g.held, g.lastRecord
    local now = GetTime()
    -- Plates still shown with the same name keep their slot.
    local out, keep, rest = {}, {}, {}
    for _, r in ipairs(found) do
        local s = plateSlot[r.frame]
        if s and s.name == r.name and not out[s.slot] then
            out[s.slot] = r
            keep[s.slot] = true
        else
            rest[#rest + 1] = r
        end
    end
    -- A unit whose plate went away (camera turned, walked off) holds its slot for HOLD
    -- seconds; if its plate comes back in that time it gets the same slot again.
    for f, s in pairs(plateSlot) do
        local last = lastRecord[s.slot]
        if not keep[s.slot] and not held[s.slot] and last and last.hp ~= 0 then
            held[s.slot] = { name = s.name, n = last.n, since = now }
        end
    end
    plateSlot = {}
    g.plateSlot = plateSlot
    for _, r in ipairs(found) do
        for i, rr in pairs(out) do if rr == r then plateSlot[r.frame] = { slot = i, name = r.name } end end
    end
    local new = {}
    for _, r in ipairs(rest) do
        local back
        for i, h in pairs(held) do
            if h.name == r.name and h.n == r.n and not out[i] then back = i; break end
        end
        if not back then
            for i, h in pairs(held) do
                if h.name == r.name and not out[i] then back = i; break end
            end
        end
        if back then
            out[back] = r
            held[back] = nil
            plateSlot[r.frame] = { slot = back, name = r.name }
        else
            new[#new + 1] = r
        end
    end
    for i, h in pairs(held) do
        if out[i] or now - h.since > HOLD then held[i] = nil end
    end
    -- New plates fill the first empty slots, nearest first (nearer units are drawn lower on
    -- the screen). A reserved (held) slot counts as empty: the reservation only helps a
    -- returning unit while nobody else has taken the slot, so new units never skip ahead.
    table.sort(new, function(a, b) return a.y < b.y end)
    for _, r in ipairs(new) do
        local slot
        for i = 1, size do
            if not out[i] then slot = i; break end
        end
        if not slot then break end
        held[slot] = nil
        out[slot] = r
        plateSlot[r.frame] = { slot = slot, name = r.name }
    end
    -- Held slots are not shown (the box is empty), just kept free for the unit coming back.
    for i = 1, size do
        local r = out[i]
        if r then lastRecord[i] = { name = r.name, hp = r.hp, class = r.class, flags = r.flags, num = r.num, n = r.n }
        elseif not held[i] then lastRecord[i] = nil end
    end
    return out
end

local function newGroup() return { plateSlot = {}, held = {}, lastRecord = {} } end
local npcGroup, friendlyGroup, playerGroup = newGroup(), newGroup(), newGroup()

-- Totems listed in Nearby (the rest are left out: there are many, and they rarely matter).
local LISTED_TOTEMS = { ["Earthbind Totem"] = true }

-- What the app's Nearby list includes besides enemies (set by the open key, see OPEN_KEYS).
local showFriendlyNPCs, showFriendlyPlayers = false, true

-- Nearby enemies (slots 40-49): hostile and neutral NPCs and enemy players. Friendly NPCs
-- (slots 50-59, the app's NPC tab). Nearby friendlies (slots 60-69): friendly players and/or
-- friendly NPCs, as asked for.
local function nearby()
    local found, targetName = platesShown()
    local npcs, friendly, players, targetShown = {}, {}, {}, false
    for _, r in ipairs(found) do
        if bit.band(r.flags, 4) ~= 0 then targetShown = true end
        local isFriend, isPlayer = bit.band(r.flags, 32) ~= 0, bit.band(r.flags, 64) ~= 0
        if not isPlayer and r.name:find("Totem") and not LISTED_TOTEMS[r.name] then
            -- Other totems: plates only (enemy totem plates are on for Earthbind).
        elseif isFriend and isPlayer then
            if showFriendlyPlayers then players[#players + 1] = r end
        elseif isFriend then
            friendly[#friendly + 1] = r
            if showFriendlyNPCs then players[#players + 1] = r end
        else
            npcs[#npcs + 1] = r
        end
    end
    return slotGroup(npcGroup, npcs, targetName, targetShown, 20), slotGroup(friendlyGroup, friendly, targetName, targetShown),
        slotGroup(playerGroup, players, targetName, targetShown, 20)
end

---------------------------------------------------------------------------
-- Target buttons and keys
---------------------------------------------------------------------------
local KEYS = {}
-- Punctuation keys (WoW's names); "-" and "=" are also bound by their other names.
local PUNCT = { "-", "=", "[", "]", ";", "'", ",", ".", "/", "`" }
local KEY_ALIAS = { ["-"] = "MINUS", ["="] = "EQUALS" }
do
    local digits = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0" }
    for i = 0, 4 do KEYS[i] = "ALT-CTRL-SHIFT-" .. digits[i + 1] end
    for i = 0, 4 do KEYS[30 + i] = "ALT-CTRL-SHIFT-" .. digits[i + 6] end
    local team = { "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P" }
    local enemy = { "A", "S", "D", "F", "G", "H", "J", "Z", "X", "C" }
    for i = 0, 9 do
        KEYS[10 + i] = "ALT-CTRL-SHIFT-" .. team[i + 1]
        KEYS[20 + i] = "ALT-CTRL-SHIFT-" .. enemy[i + 1]
        KEYS[40 + i] = "ALT-CTRL-" .. digits[i + 1]
        KEYS[50 + i] = "ALT-CTRL-" .. team[i + 1]
        KEYS[60 + i] = "ALT-CTRL-" .. enemy[i + 1]
        -- Nearby enemies 11-20 and friendlies 11-20.
        KEYS[70 + i] = "ALT-CTRL-" .. PUNCT[i + 1]
        KEYS[80 + i] = "ALT-CTRL-SHIFT-" .. PUNCT[i + 1]
        -- Battleground team 11-15 (90-94) and enemies 11-15 (95-99).
        KEYS[90 + i] = "ALT-SHIFT-" .. digits[i + 1]
    end
    -- Raid members 1-25 (100-124): Alt+Shift and a letter (not L: ThorControls' Leave Party).
    local letters = "ABCDEFGHIJKMNOPQRSTUVWXYZ"
    for i = 0, 24 do KEYS[100 + i] = "ALT-SHIFT-" .. letters:sub(i + 1, i + 1) end
end
local UNIT_SLOTS = { [0] = "player", "party1", "party2", "party3", "party4" }
for i = 1, 5 do UNIT_SLOTS[29 + i] = "arena" .. i end
for i = 1, 25 do UNIT_SLOTS[99 + i] = "raid" .. i end
-- Focus and target keys on Alt+Ctrl+F1-F4: letters with Alt+Ctrl+Shift are the open keys,
-- and WoW falls back to a key without Shift when that combination isn't bound yet.
-- Focus (125): Alt+Ctrl+F1.
KEYS[125] = "ALT-CTRL-F1"
UNIT_SLOTS[125] = "focus"
-- Clear target (126, Alt+Ctrl+F2): tapping your current target's box again.
KEYS[126] = "ALT-CTRL-F2"
-- Focus your target (127, Alt+Ctrl+F3) and clear the focus (128, Alt+Ctrl+F4): the app's
-- Set Focus and Clear Focus buttons, and a double tap on a box.
KEYS[127] = "ALT-CTRL-F3"
KEYS[128] = "ALT-CTRL-F4"
local MACRO_SLOTS = { [126] = "/cleartarget", [127] = "/focus", [128] = "/clearfocus" }

local buttons, slotName = {}, {}
local keyOwner = CreateFrame("Frame")
local function makeButtons()
    for slot, key in pairs(KEYS) do
        local b = CreateFrame("Button", "WoWMobileLinkTarget" .. slot, UIParent, "SecureActionButtonTemplate")
        b:RegisterForClicks("AnyDown")
        if UNIT_SLOTS[slot] then
            b:SetAttribute("type", "target")
            b:SetAttribute("unit", UNIT_SLOTS[slot])
        else
            b:SetAttribute("type", "macro")
            b:SetAttribute("macrotext", MACRO_SLOTS[slot] or "")
        end
        buttons[slot] = b
        SetOverrideBindingClick(keyOwner, true, key, b:GetName(), "LeftButton")
        local last = key:match("[^-]+$") or (key:sub(-1) == "-" and "-")
        if KEY_ALIAS[last] then
            SetOverrideBindingClick(keyOwner, true, key:sub(1, -2) .. KEY_ALIAS[last], b:GetName(), "LeftButton")
        end
    end
end

-- Puts the records of one name group (battleground team/enemies, nearby) into slots
-- base..base+9, keeping each name on its button while in combat.
-- records may have gaps (nearby keeps units in fixed slots): index i goes to slot base+i-1.
local function assign(list, base, records, count)
    count = count or 10
    if not next(buttons) then
        for i = 1, count do
            local r = records[i]
            if r then
                r.slot, r.flags = base + i - 1, r.flags + 128
                list[#list + 1] = r
            end
        end
        return
    end
    if not InCombatLockdown() then
        for i = 0, count - 1 do
            local r = records[i + 1]
            local name = r and r.name or ""
            if slotName[base + i] ~= name then
                slotName[base + i] = name
                buttons[base + i]:SetAttribute("macrotext", name ~= "" and ("/targetexact " .. name) or "")
            end
            if r then r.slot = base + i; list[#list + 1] = r end
        end
        return
    end
    -- In combat the buttons keep their names: a unit stays in its own slot when that slot's
    -- button has its name, else moves to a slot whose button does; others get flag 128.
    local used, pending = {}, {}
    for i = 1, count do
        local r = records[i]
        if r and slotName[base + i - 1] == r.name then
            used[i - 1], r.slot = true, base + i - 1
            list[#list + 1] = r
        elseif r then
            pending[#pending + 1] = i
        end
    end
    local later = {}
    for _, i in ipairs(pending) do
        local r, placed = records[i], false
        for k = 0, count - 1 do
            if not used[k] and slotName[base + k] == r.name then
                used[k], placed, r.slot = true, true, base + k
                list[#list + 1] = r
                break
            end
        end
        if not placed then later[#later + 1] = i end
    end
    for _, i in ipairs(later) do
        local r = records[i]
        local k = not used[i - 1] and i - 1
        if not k then
            for j = 0, count - 1 do if not used[j] then k = j; break end end
        end
        if k then
            used[k], r.slot, r.flags = true, base + k, r.flags + 128
            list[#list + 1] = r
        end
    end
end

local lastScoreRequest = 0
-- Battleground players listed per side (Arathi Basin and Eye of the Storm have 15).
local BG_MAX = 15
local function collect()
    local _, instance = IsInInstance()
    local mode = MODE[instance or "none"] or 0
    local list = {}
    if mode == 3 then
        -- Battleground: team from the raid, enemies from the scoreboard, 15 each (slots
        -- 10-19 then 90-94 for the team, 20-29 then 95-99 for enemies).
        local team = {}
        for i = 1, GetNumRaidMembers() do
            if #team >= BG_MAX then break end
            local unit = "raid" .. i
            if UnitExists(unit) then team[#team + 1] = unitRecord(0, unit) end
        end
        local t1, t2 = {}, {}
        for i = 1, 10 do t1[i] = team[i] end
        for i = 1, 5 do t2[i] = team[i + 10] end
        assign(list, 10, t1)
        assign(list, 90, t2, 5)
        if GetTime() - lastScoreRequest > 5 then
            lastScoreRequest = GetTime()
            RequestBattlefieldScoreData()
        end
        local myFaction = UnitFactionGroup("player") == "Horde" and 0 or 1
        -- By name, not scoreboard order (that changes with every kill), so boxes stay put.
        local seen, enemyNames, tokens, enemies = visibleEnemies(), {}, {}, {}
        for i = 1, GetNumBattlefieldScores() do
            local name, _, _, _, _, faction, _, _, _, token = GetBattlefieldScore(i)
            if name and faction ~= myFaction then
                name = strsplit("-", name)
                enemyNames[#enemyNames + 1] = name
                tokens[name] = token
            end
        end
        table.sort(enemyNames)
        for _, name in ipairs(enemyNames) do
            if #enemies >= BG_MAX then break end
            local unit = seen[name]
            if unit then
                enemies[#enemies + 1] = unitRecord(0, unit, name)
            else
                enemies[#enemies + 1] = { slot = 0, class = CLASS[tokens[name] or ""] or 0, hp = 255, flags = 2 + 8 + 64, name = name }
            end
        end
        local e1, e2 = {}, {}
        for i = 1, 10 do e1[i] = enemies[i] end
        for i = 1, 5 do e2[i] = enemies[i + 10] end
        assign(list, 20, e1)
        assign(list, 95, e2, 5)
    else
        list[#list + 1] = unitRecord(0, "player")
        for i = 1, 4 do
            if UnitExists("party" .. i) then list[#list + 1] = unitRecord(i, "party" .. i) end
        end
        if mode == 4 then
            for i = 1, 5 do
                if UnitExists("arena" .. i) then list[#list + 1] = unitRecord(29 + i, "arena" .. i) end
            end
        end
    end
    -- Focus, in every mode.
    if UnitExists("focus") then list[#list + 1] = unitRecord(125, "focus") end
    if UnitExists("target") then list[#list + 1] = unitRecord(129, "target") end
    -- Raid members (the app's Raid Heal mode), with their raid group in num.
    if mode ~= 3 then
        for i = 1, math.min(25, GetNumRaidMembers()) do
            local unit = "raid" .. i
            if UnitExists(unit) then
                local _, _, group = GetRaidRosterInfo(i)
                local r = unitRecord(99 + i, unit)
                r.num = group or 0
                list[#list + 1] = r
            end
        end
    end
    local npcs, friendly, players = nearby()
    local function split(records)
        local a, b = {}, {}
        for i = 1, 10 do a[i], b[i] = records[i], records[i + 10] end
        return a, b
    end
    local e1, e2 = split(npcs)
    local f1, f2 = split(players)
    assign(list, 40, e1)
    assign(list, 70, e2)
    assign(list, 50, friendly)
    assign(list, 60, f1)
    assign(list, 80, f2)
    if not next(buttons) then
        for _, r in ipairs(list) do
            if UNIT_SLOTS[r.slot] and r.flags < 128 then r.flags = r.flags + 128 end
        end
    end
    return mode, list
end

-- One packet: header plus as many records as fit, rotating through the list.
local function build()
    local mode, list = collect()
    seq = (seq + 1) % 256
    local focus = GetMouseFocus()
    local overUi = focus and focus ~= WorldFrame and focus:GetParent() ~= WorldFrame
    local b = { 87, 77, VERSION, seq, mode + (overUi and 128 or 0), #list, 0 }
    local count = 0
    if #list > 0 then
        if cursor >= #list then cursor = 0 end
        local start = cursor
        repeat
            local r = list[cursor + 1]
            local name = r.name:sub(1, 24)
            if #b + 8 + #name + 2 > MAX_BYTES then break end
            b[#b + 1] = r.slot; b[#b + 1] = r.class; b[#b + 1] = r.hp; b[#b + 1] = r.flags
            b[#b + 1] = r.px or 0; b[#b + 1] = r.py or 0; b[#b + 1] = r.num or 0
            b[#b + 1] = #name
            for k = 1, #name do b[#b + 1] = name:byte(k) end
            count = count + 1
            cursor = (cursor + 1) % #list
        until cursor == start
    end
    b[7] = count
    local crc = crc16(b, #b)
    b[#b + 1] = math.floor(crc / 256)
    b[#b + 1] = crc % 256
    return b
end

local function draw()
    local b = build()
    local bitsLeft, acc, idx = 0, 0, 1
    for i = 1, CELLS do
        local v = 0
        for _ = 1, 3 do
            if bitsLeft == 0 then
                acc = b[idx] or 0
                idx = idx + 1
                bitsLeft = 8
            end
            bitsLeft = bitsLeft - 1
            v = v * 2 + (math.floor(acc / 2 ^ bitsLeft) % 2)
        end
        -- Only cells whose colour changed (hundreds of SetTexture calls per update add up).
        if cellValue[i] ~= v then
            cellValue[i] = v
            cells[i]:SetTexture(v % 2, math.floor(v / 2) % 2, math.floor(v / 4) % 2, 1)
        end
    end
end

local sinceDraw = 0
strip:SetScript("OnUpdate", function(_, elapsed)
    sinceDraw = sinceDraw + elapsed
    if sinceDraw < INTERVAL then return end
    sinceDraw = 0
    draw()
end)

function WoWMobileLink_SetActive(on)
    -- Already in that state: nothing to do (laying out 320 cells again is not free).
    if active == not not on then return end
    active = not not on
    if active then
        layout()
        draw()
        strip:Show()
    else
        strip:Hide()
    end
end

-- Numbers on plates are kept up while the Target page is closed too (the strip does it
-- while open), so a unit keeps its number when you switch pages.
local numberer, sinceNumbers = CreateFrame("Frame"), 0
numberer:SetScript("OnUpdate", function(_, elapsed)
    sinceNumbers = sinceNumbers + elapsed
    if active or sinceNumbers < NUMBER_INTERVAL then return end
    sinceNumbers = 0
    platesShown()
end)

-- Open keys: each turns the strip on and says what Nearby includes besides enemies
-- (friendly NPCs, friendly players). The app sends one when the page opens and when the
-- Targeting settings change; a single key, so ConsolePort redraws its buttons only once.
local OPEN_KEYS = { K = { false, true }, M = { false, false }, N = { true, true }, B = { true, false } }
local openButtons = {}
for key, opt in pairs(OPEN_KEYS) do
    local b = CreateFrame("Button", "WoWMobileLinkOpen" .. key)
    b:SetScript("OnClick", function()
        if showFriendlyNPCs ~= opt[1] or showFriendlyPlayers ~= opt[2] then
            showFriendlyNPCs, showFriendlyPlayers = opt[1], opt[2]
            -- Units change lists: start the groups afresh.
            npcGroup, playerGroup = newGroup(), newGroup()
        end
        WoWMobileLink_SetActive(true)
    end)
    openButtons[key] = b
end

-- Nameplates are what Nearby is built from: friendly and enemy plates on, and enemy
-- players' plates in class colours (the only way to tell them from NPCs). Done once per
-- account, so they stay off if switched off later.
local function defaultNameplates()
    WoWMobileLinkDB = WoWMobileLinkDB or {}
    if WoWMobileLinkDB.platesDefaulted then return end
    WoWMobileLinkDB.platesDefaulted = true
    SetCVar("nameplateShowEnemies", "1")
    SetCVar("nameplateShowFriends", "1")
    pcall(SetCVar, "ShowClassColorInNameplate", "1") -- not on every client build
end

-- Enemy totem plates, so Earthbind Totem can be listed in Nearby. Once per account.
local function defaultTotemPlates()
    WoWMobileLinkDB = WoWMobileLinkDB or {}
    if WoWMobileLinkDB.totemsDefaulted then return end
    WoWMobileLinkDB.totemsDefaulted = true
    pcall(SetCVar, "nameplateShowEnemyTotems", "1")
end

local owner = CreateFrame("Frame")
local function bindKeys()
    if InCombatLockdown() then owner.pending = true; return end
    owner.pending = nil
    if not next(buttons) then makeButtons() end
    for key, b in pairs(openButtons) do SetOverrideBindingClick(owner, true, "ALT-CTRL-SHIFT-" .. key, b:GetName()) end
    SetOverrideBinding(owner, true, "ALT-CTRL-SHIFT-L", "WOWMOBILELINK_OFF")
end
owner:RegisterEvent("PLAYER_LOGIN")
owner:RegisterEvent("PLAYER_REGEN_ENABLED")
owner:RegisterEvent("UI_SCALE_CHANGED")
owner:RegisterEvent("DISPLAY_SIZE_CHANGED")
owner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" then
        defaultNameplates()
        defaultTotemPlates()
        bindKeys()
    elseif event == "PLAYER_REGEN_ENABLED" then
        if owner.pending then bindKeys() end
    elseif active then
        layout()
    end
end)

SLASH_WOWMOBILELINK1 = "/wml"
SlashCmdList.WOWMOBILELINK = function(arg)
    arg = strlower(strtrim(arg or ""))
    if arg == "on" then WoWMobileLink_SetActive(true)
    elseif arg == "off" then WoWMobileLink_SetActive(false) end
    DEFAULT_CHAT_FRAME:AddMessage("WoW Mobile Link: " .. (active and "on" or "off"))
end
