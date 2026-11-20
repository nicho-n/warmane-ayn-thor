-- !ThorRecommended: the Warmane Ayn Thor app's recommended addon settings.
--
-- Installed with the app's "recommended addons". It fills in each recommended addon's
-- saved settings, from Data.lua, only where that addon has none yet: a fresh install
-- (account-wide settings) or a character that hasn't used the addon (per-character
-- settings). Nobody's own settings are ever replaced.
--
-- The "!" makes WoW load this before the other addons, so its ADDON_LOADED handler runs
-- after an addon's saved variables are read but before the addon itself looks at them.
-- The chat window (opacity, colour, size, position) is set up once per character.

local data = ThorRecommendedData
if type(data) ~= "table" then return end

local function copy(value)
    if type(value) ~= "table" then return value end
    local out = {}
    for k, v in pairs(value) do out[k] = copy(v) end
    return out
end

local function charKey()
    return (UnitName("player") or "?") .. " - " .. (GetRealmName() or "?")
end

-- SexyMap keeps one profile per character (AceDB without a default profile). Its
-- recommended profile is added under its own name; characters without a profile of
-- their own are pointed at it.
local SEXYMAP_PROFILE = "Recommended"
local function applySexyMap()
    local rec = data.vars.SexyMapDB
    if not rec then return end
    if SexyMapDB == nil then SexyMapDB = { namespaces = {}, profileKeys = {} } end
    SexyMapDB.namespaces = SexyMapDB.namespaces or {}
    SexyMapDB.profileKeys = SexyMapDB.profileKeys or {}
    for name, ns in pairs(rec.namespaces or {}) do
        local target = SexyMapDB.namespaces[name] or {}
        SexyMapDB.namespaces[name] = target
        local profile = ns.profiles and ns.profiles[SEXYMAP_PROFILE]
        if profile then
            target.profiles = target.profiles or {}
            if target.profiles[SEXYMAP_PROFILE] == nil then target.profiles[SEXYMAP_PROFILE] = copy(profile) end
        end
    end
    if rec.profiles and rec.profiles[SEXYMAP_PROFILE] then
        SexyMapDB.profiles = SexyMapDB.profiles or {}
        if SexyMapDB.profiles[SEXYMAP_PROFILE] == nil then
            SexyMapDB.profiles[SEXYMAP_PROFILE] = copy(rec.profiles[SEXYMAP_PROFILE])
        end
    end
    local key = charKey()
    if SexyMapDB.profileKeys[key] == nil then SexyMapDB.profileKeys[key] = SEXYMAP_PROFILE end
end

local function applyAddon(addon)
    if addon == "SexyMap" then return applySexyMap() end
    for _, var in ipairs(data.addons[addon] or {}) do
        if _G[var] == nil and data.vars[var] ~= nil then _G[var] = copy(data.vars[var]) end
    end
end

-- The General chat window: background colour and opacity, font size, size and position.
local function applyChat()
    local c = data.chat
    local frame = ChatFrame1
    if not c or not frame then return end
    if c.color and FCF_SetWindowColor then FCF_SetWindowColor(frame, c.color[1], c.color[2], c.color[3]) end
    if c.alpha and FCF_SetWindowAlpha then FCF_SetWindowAlpha(frame, c.alpha) end
    if c.fontSize and FCF_SetChatWindowFontSize then FCF_SetChatWindowFontSize(nil, frame, c.fontSize) end
    if c.x and c.y and c.width and c.height then
        local w, h = UIParent:GetWidth(), UIParent:GetHeight()
        frame:ClearAllPoints()
        frame:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", c.x * w, c.y * h)
        frame:SetWidth(c.width)
        frame:SetHeight(c.height)
        if frame.SetUserPlaced then frame:SetUserPlaced(true) end
        if FCF_SavePositionAndDimensions then FCF_SavePositionAndDimensions(frame) end
    end
end

-- ShadowedUnitFrames "Show party as raid" (the recommended setup: party frames off, the
-- raid frames show the party too) didn't work: SUF only tells the raid frames to show a
-- party when its party frames are on (Units:CheckGroupVisibility needs the party header), so
-- in a 5-player group, a dungeon, no frames showed at all. Done here when SUF skips it.
local function fixShadowedPartyAsRaid()
    local suf = ShadowUF
    local units = suf and suf.Units
    if not units or not units.CheckGroupVisibility or not units.headerFrames then return end
    hooksecurefunc(units, "CheckGroupVisibility", function(self)
        local profile = suf.db and suf.db.profile
        if not profile or not profile.locked or InCombatLockdown() then return end
        local headers, raidConfig = self.headerFrames, profile.units.raid
        if headers.party or not raidConfig or not raidConfig.showParty then return end
        if not (suf.enabledUnits and suf.enabledUnits.raid) then return end
        local raid = headers.raid and not raidConfig.frameSplit and headers.raid or headers.raidParent
        if not raid then return end
        local showPlayer = profile.units.party and profile.units.party.showPlayer
        if showPlayer == nil then showPlayer = true end
        raid:SetAttribute("showPlayer", showPlayer)
        raid:SetAttribute("showParty", true)
    end)
    -- Frames SUF set up already.
    if suf.db and not InCombatLockdown() then units:CheckGroupVisibility() end
end

local events = CreateFrame("Frame")
events:RegisterEvent("ADDON_LOADED")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:SetScript("OnEvent", function(self, event, addon)
    if event == "ADDON_LOADED" then
        if addon == "!ThorRecommended" then
            ThorRecommendedChar = ThorRecommendedChar or {}
        elseif data.addons[addon] then
            applyAddon(addon)
        end
        if addon == "ShadowedUnitFrames" then fixShadowedPartyAsRaid() end
    elseif event == "PLAYER_ENTERING_WORLD" then
        self:UnregisterEvent("PLAYER_ENTERING_WORLD")
        ThorRecommendedChar = ThorRecommendedChar or {}
        if not ThorRecommendedChar.chat and not InCombatLockdown() then
            applyChat()
            ThorRecommendedChar.chat = true
        end
    end
end)
