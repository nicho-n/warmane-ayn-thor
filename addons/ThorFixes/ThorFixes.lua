-- Thor Fixes: personal overrides for WoW Imito + ConsolePort on the AYN Thor.
-- Kept in its own addon because WoW Imito reinstalls its bundled addons
-- (WoWImito, ConsolePort*), which undid edits made directly to their files.
--
--  1. Camera:   right stick no longer right-clicks NPCs (pure mouselook).
--  2. Pet bar:  hides Blizzard's default pet bar (ConsolePort has its own).
--  3. XP bar:   lifts ConsolePort's XP/reputation bar off the screen edge.
--  4. Triggers: hides the big L2/R2 icons on the ConsolePort bar (toggleable).
--  5. WoW Mobile: hides WoW Imito's coloured status strip, but only when the
--     game runs in the WoW Mobile app (which sets gxMaximize; WoW Imito removes it).
--
-- /thorfixes for status and options.

BINDING_HEADER_THORFIXES = "Thor Fixes"
BINDING_NAME_THORFIXES_CAMERALOOK = "Camera look (no click)"

local defaults = { camera = true, petbar = true, xpLift = 10, hideTriggers = true }
local db
local pending = {}
local events = CreateFrame("Frame")

local function msg(text)
    DEFAULT_CHAT_FRAME:AddMessage("|cff69ccf0Thor Fixes:|r " .. text)
end

local function onoff(value) return value and "|cff00ff00on|r" or "|cffff4040off|r" end

---------------------------------------------------------------------------
-- 1. Camera: WoW Imito holds right mouse / NUMPADDIVIDE (TURNORACTION) while
-- the right stick moves. A short or tiny hold counts as a right-click and
-- interacts with the NPC under the pointer. While ConsolePort is active,
-- point both keys at a mouselook-only binding that never clicks.
---------------------------------------------------------------------------
local cameraOwner = CreateFrame("Frame", "ThorFixesCameraOwner", UIParent)

local function consolePortActive()
    local C = WoWImito and WoWImito.Controller
    return C and C.cpActive
end

local function applyCamera()
    if InCombatLockdown() then pending.camera = true; return end
    pending.camera = nil
    ClearOverrideBindings(cameraOwner)
    if db and db.camera and consolePortActive() then
        SetOverrideBinding(cameraOwner, true, "BUTTON2", "THORFIXES_CAMERALOOK")
        SetOverrideBinding(cameraOwner, true, "NUMPADDIVIDE", "THORFIXES_CAMERALOOK")
    end
end

local function hookController()
    local C = WoWImito and WoWImito.Controller
    if C and C.Switched and not C.thorFixesHooked then
        C.thorFixesHooked = true
        -- Called whenever WoW Imito switches between touch UI and ConsolePort.
        hooksecurefunc(C, "Switched", applyCamera)
    end
end

---------------------------------------------------------------------------
-- 2. Pet bar: WoW Imito only hides Blizzard's pet bar in touch mode. Keep the
-- frame alive (touch pet buttons click it) but never draw or hit-test it.
---------------------------------------------------------------------------
local petHooked

local function applyPetBar()
    local bar = PetActionBarFrame
    if not db or not db.petbar or not bar then return end
    bar:SetAlpha(0)
    if not petHooked then
        petHooked = true
        bar:HookScript("OnShow", function(self) if db.petbar then self:SetAlpha(0) end end)
        hooksecurefunc(bar, "SetAlpha", function(self, alpha)
            if db.petbar and alpha and alpha > 0 and not self.thorFixesSetting then
                self.thorFixesSetting = true
                self:SetAlpha(0)
                self.thorFixesSetting = nil
            end
        end)
    end
    if InCombatLockdown() then pending.pet = true; return end
    pending.pet = nil
    bar:EnableMouse(false)
    for i = 1, (NUM_PET_ACTION_SLOTS or 10) do
        local button = _G["PetActionButton" .. i]
        if button then button:EnableMouse(false) end
    end
end

---------------------------------------------------------------------------
-- 3. XP bar: ConsolePort places its XP/reputation bars at the very bottom
-- edge, where the Thor cuts off about half of them. Lift them up.
---------------------------------------------------------------------------
local function liftBar(bar)
    local lift = db and db.xpLift or 0
    if lift == 0 or not bar or bar:GetNumPoints() == 0 then return end
    local point, relativeTo, relativePoint, x, y = bar:GetPoint(1)
    bar:SetPoint(point, relativeTo, relativePoint, x, (y or 0) + lift)
end

local function relayoutWatchBars()
    local container = ConsolePortBarWatchBars
    if container and container.UpdateBarsShown then container:UpdateBarsShown() end
end

local function hookWatchBars()
    local container = ConsolePortBarWatchBars
    if not container or container.thorFixesHooked or not container.LayoutBar then return end
    container.thorFixesHooked = true
    hooksecurefunc(container, "LayoutBar", function(_, bar) liftBar(bar) end)
    relayoutWatchBars()
end

---------------------------------------------------------------------------
-- 4. L2/R2 trigger icons: CP_T3/CP_T4 placeholder buttons on the ConsolePort
-- bar. WoW Imito gives them no checkbox, so control them from here.
-- Applied when ConsolePortBar loads (before it builds the bar) or on reload.
---------------------------------------------------------------------------
local TRIGGER_LAYOUT = {
    CP_T3 = { point = { "LEFT", 456, 56 }, dir = "right", size = 64 },
    CP_T4 = { point = { "RIGHT", -456, 56 }, dir = "left", size = 64 },
}

local function copy(t)
    local out = {}
    for k, v in pairs(t) do out[k] = type(v) == "table" and copy(v) or v end
    return out
end

local function applyTriggers()
    local cfg = ConsolePortBarSetup
    if not db or not cfg or not cfg.layout then return false end
    for id, geometry in pairs(TRIGGER_LAYOUT) do
        if db.hideTriggers then
            cfg.layout[id] = nil
        elseif not cfg.layout[id] then
            cfg.layout[id] = copy(geometry)
        end
    end
    return true
end

---------------------------------------------------------------------------
-- 5. WoW Mobile: WoWImito draws a row of coloured cells at the bottom of the
-- screen for the WoW Imito app to read. The WoW Mobile app doesn't read it.
-- WoW Mobile sets the gxMaximize CVar before every launch and WoW Imito's
-- launcher removes it, so gxMaximize = 1 means "running in WoW Mobile": hide
-- the strip for this session. (A Scroll Lock press also counts.)
---------------------------------------------------------------------------
local signalOwner = CreateFrame("Frame", "ThorFixesWowMobileOwner", UIParent)
local inWowMobile, stripHooked = false, false

local function hideStrip()
    local strip = WoWImitoStateStrip
    if not strip then return end
    if not stripHooked then
        stripHooked = true
        hooksecurefunc(strip, "Show", function(self) if inWowMobile then self:Hide() end end)
    end
    strip:Hide()
end

function ThorFixes_WowMobileSignal()
    inWowMobile = true
    hideStrip()
end

local function detectWowMobile()
    if GetCVar("gxMaximize") == "1" then
        inWowMobile = true
        hideStrip()
    end
end

local function bindSignal()
    if InCombatLockdown() then pending.signal = true; return end
    pending.signal = nil
    -- Every modifier combination, so it still arrives while a trigger holds Shift/Ctrl.
    for _, mod in ipairs({"", "SHIFT-", "CTRL-", "CTRL-SHIFT-", "ALT-", "ALT-SHIFT-", "ALT-CTRL-", "ALT-CTRL-SHIFT-"}) do
        SetOverrideBinding(signalOwner, true, mod .. "SCROLLLOCK", "THORFIXES_WOWMOBILE")
    end
end

---------------------------------------------------------------------------
-- Events
---------------------------------------------------------------------------
events:RegisterEvent("ADDON_LOADED")
events:RegisterEvent("PLAYER_LOGIN")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:RegisterEvent("PLAYER_REGEN_ENABLED")
events:RegisterEvent("UNIT_PET")
events:SetScript("OnEvent", function(_, event, arg1)
    if event == "ADDON_LOADED" then
        if arg1 == "ThorFixes" then
            ThorFixesDB = ThorFixesDB or {}
            db = ThorFixesDB
            for k, v in pairs(defaults) do if db[k] == nil then db[k] = v end end
        elseif arg1 == "ConsolePortBar" then
            applyTriggers()
            hookWatchBars()
        end
    elseif event == "PLAYER_LOGIN" then
        bindSignal()
        hookController()
        if IsAddOnLoaded("ConsolePortBar") then hookWatchBars() end
        applyPetBar()
        applyCamera()
    elseif event == "PLAYER_ENTERING_WORLD" then
        detectWowMobile()
        hookController()
        applyPetBar()
        applyCamera()
    elseif event == "UNIT_PET" then
        if arg1 == "player" then applyPetBar() end
    elseif event == "PLAYER_REGEN_ENABLED" then
        if pending.camera then applyCamera() end
        if pending.pet then applyPetBar() end
        if pending.signal then bindSignal() end
    end
end)

---------------------------------------------------------------------------
-- /thorfixes
---------------------------------------------------------------------------
SLASH_THORFIXES1 = "/thorfixes"
SLASH_THORFIXES2 = "/tf"
SlashCmdList.THORFIXES = function(input)
    local cmd, value = strsplit(" ", strlower(strtrim(input or "")), 2)
    if cmd == "xp" and tonumber(value) then
        db.xpLift = tonumber(value)
        relayoutWatchBars()
        msg("XP bar lift set to " .. db.xpLift .. ". (If the bar didn't move, /reload.)")
    elseif cmd == "camera" and (value == "on" or value == "off") then
        db.camera = value == "on"
        applyCamera()
        msg("Camera fix " .. onoff(db.camera))
    elseif cmd == "petbar" and (value == "on" or value == "off") then
        db.petbar = value == "on"
        if db.petbar then applyPetBar() else msg("Default pet bar will be back after /reload.") end
        msg("Hide default pet bar " .. onoff(db.petbar))
    elseif cmd == "triggers" and (value == "on" or value == "off") then
        if InCombatLockdown() then msg("Leave combat first."); return end
        db.hideTriggers = value == "off"
        if applyTriggers() then ReloadUI() else msg("ConsolePort bar isn't loaded; the change applies next time it loads.") end
    else
        msg("Camera fix " .. onoff(db.camera) .. " | Hide default pet bar " .. onoff(db.petbar) ..
            " | L2/R2 icons " .. onoff(not db.hideTriggers) .. " | XP bar lift " .. (db.xpLift or 0))
        msg("/thorfixes xp <number>  - move the XP bar up (e.g. 10, 20; 0 = original)")
        msg("/thorfixes camera on|off  - right stick camera without clicks")
        msg("/thorfixes petbar on|off  - hide Blizzard's default pet bar")
        msg("/thorfixes triggers on|off  - show the L2/R2 icons on the ConsolePort bar (reloads)")
    end
end
