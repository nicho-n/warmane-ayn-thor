-- Thor Controls: makes stock ConsolePortLK work with the Warmane Ayn Thor Launcher's
-- controller mapping, plus a few layout fixes for the Thor's screen.
--
--  1. Calibration: the launcher sends one key per controller button; ConsolePort is told
--     which key is which button. Triggers are held Shift (L2) / Ctrl (R2), ConsolePort's
--     own modifiers, so they need no calibration. The left stick sends arrow keys, which
--     ConsolePort's movement handler already binds.
--  2. Camera: the right stick holds NUMPADDIVIDE while it moves the mouse; that key
--     looks around (mouselook) and never clicks, so right-click stays a normal click.
--  3. Pet bar: hides Blizzard's default pet bar (ConsolePort has its own).
--  4. XP bar: lifts ConsolePort's XP/reputation bar off the screen edge.
--  5. Triggers: hides the big L2/R2 icons on the ConsolePort bar (toggleable).
--
-- /thor for status and options.

BINDING_HEADER_THORCONTROLS = "Thor Controls"
BINDING_NAME_THORCONTROLS_CAMERALOOK = "Camera look (no click)"

-- Controller button -> key the launcher sends (see ConsolePortController.java).
local CALIBRATION = {
    CP_L_UP = "F1", CP_L_RIGHT = "F2", CP_L_DOWN = "F3", CP_L_LEFT = "F4",
    CP_X_LEFT = "F5", CP_X_RIGHT = "F6",
    CP_T1 = "F7", CP_T2 = "F8",
    CP_R_UP = "F9", CP_R_RIGHT = "F10", CP_R_DOWN = "F11", CP_R_LEFT = "F12",
}
-- ConsolePort settings that go with it: no Guide button, no extra trigger buttons
-- (L2/R2 are modifiers, L3/R3 and the right stick are handled by the launcher).
local CP_SETTINGS = {
    skipGuideBtn = true,
    skipCP_T3 = true, skipCP_T4 = true, skipCP_T5 = true, skipCP_T6 = true,
    disableStickMouse = true,
}
local MODIFIERS = { "", "SHIFT-", "CTRL-", "CTRL-SHIFT-" }

local defaults = { petbar = true, xpLift = 10, hideTriggers = true, hideTrail = true }
local db
local pending = {}
local events = CreateFrame("Frame")
local owner = CreateFrame("Frame", "ThorControlsBindingOwner", UIParent)

local function msg(text)
    DEFAULT_CHAT_FRAME:AddMessage("|cff69ccf0Thor Controls:|r " .. text)
end

local function onoff(value) return value and "|cff00ff00on|r" or "|cffff4040off|r" end

---------------------------------------------------------------------------
-- 1. Calibration. ConsolePort reads its settings when it loads (before this addon)
-- and applies the calibration with SetBinding at login, so setting the table here,
-- before PLAYER_LOGIN, is enough. Any other calibration (e.g. from /cp recalibrate)
-- is put back.
---------------------------------------------------------------------------
local function applyCalibration()
    if type(ConsolePortSettings) ~= "table" then return false end
    local settings = ConsolePortSettings
    local changed = false
    settings.calibration = settings.calibration or {}
    for button, key in pairs(CALIBRATION) do
        if settings.calibration[button] ~= key then
            settings.calibration[button] = key
            changed = true
        end
    end
    for k, v in pairs(CP_SETTINGS) do
        if settings[k] ~= v then
            settings[k] = v
            changed = true
        end
    end
    return changed
end

local function reloadConsolePortBindings()
    if InCombatLockdown() then pending.cpBindings = true; return end
    pending.cpBindings = nil
    if ConsolePort and ConsolePort.LoadBindingSet then ConsolePort:LoadBindingSet() end
    -- ConsolePort opens its calibration wizard when it finds no calibration at load.
    if ConsolePortCalibrationFrame and ConsolePortCalibrationFrame:IsShown() then
        ConsolePortCalibrationFrame:Hide()
    end
end

---------------------------------------------------------------------------
-- 2. Camera: NUMPADDIVIDE (with any trigger held) looks around without clicking.
---------------------------------------------------------------------------
local function applyCamera()
    if InCombatLockdown() then pending.camera = true; return end
    pending.camera = nil
    ClearOverrideBindings(owner)
    for _, mod in ipairs(MODIFIERS) do
        SetOverrideBinding(owner, true, mod .. "NUMPADDIVIDE", "THORCONTROLS_CAMERALOOK")
    end
end

---------------------------------------------------------------------------
-- 3. Pet bar: never draw or hit-test Blizzard's default pet bar.
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
            if db.petbar and alpha and alpha > 0 and not self.thorSetting then
                self.thorSetting = true
                self:SetAlpha(0)
                self.thorSetting = nil
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
-- 4. XP bar: ConsolePort puts it at the very bottom edge; lift it.
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
    if not container or container.thorHooked or not container.LayoutBar then return end
    container.thorHooked = true
    hooksecurefunc(container, "LayoutBar", function(_, bar) liftBar(bar) end)
    relayoutWatchBars()
end

---------------------------------------------------------------------------
-- 5. L2/R2 trigger icons (CP_T3/CP_T4 placeholders on the ConsolePort bar).
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
-- 6. Cursor trail: ConsolePort draws a controller-button icon (R3 when no interact
-- button is set) next to the pointer over units. Hidden unless turned back on.
---------------------------------------------------------------------------
local trailHooked

local function applyTrail()
    local trail = ConsolePortCursorTrail
    if not db or not trail then return end
    if not trailHooked then
        trailHooked = true
        hooksecurefunc(trail, "Show", function(self) if db.hideTrail then self:Hide() end end)
    end
    if db.hideTrail then trail:Hide() else trail:Show() end
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
        if arg1 == "ThorControls" then
            ThorControlsDB = ThorControlsDB or {}
            db = ThorControlsDB
            for k, v in pairs(defaults) do if db[k] == nil then db[k] = v end end
            applyCalibration()
        elseif arg1 == "ConsolePortBar" then
            applyTriggers()
            hookWatchBars()
        end
    elseif event == "PLAYER_LOGIN" then
        applyCalibration()
        reloadConsolePortBindings()
        applyCamera()
        if IsAddOnLoaded("ConsolePortBar") then hookWatchBars() end
        applyPetBar()
        applyTrail()
    elseif event == "PLAYER_ENTERING_WORLD" then
        applyPetBar()
        applyTrail()
    elseif event == "UNIT_PET" then
        if arg1 == "player" then applyPetBar() end
    elseif event == "PLAYER_REGEN_ENABLED" then
        if pending.cpBindings then reloadConsolePortBindings() end
        if pending.camera then applyCamera() end
        if pending.pet then applyPetBar() end
    end
end)

---------------------------------------------------------------------------
-- /thor
---------------------------------------------------------------------------
SLASH_THORCONTROLS1 = "/thor"
SlashCmdList.THORCONTROLS = function(input)
    local cmd, value = strsplit(" ", strlower(strtrim(input or "")), 2)
    if cmd == "xp" and tonumber(value) then
        db.xpLift = tonumber(value)
        relayoutWatchBars()
        msg("XP bar lift set to " .. db.xpLift .. ". (If the bar didn't move, /reload.)")
    elseif cmd == "petbar" and (value == "on" or value == "off") then
        db.petbar = value == "on"
        if db.petbar then applyPetBar() else msg("Default pet bar will be back after /reload.") end
        msg("Hide default pet bar " .. onoff(db.petbar))
    elseif cmd == "triggers" and (value == "on" or value == "off") then
        if InCombatLockdown() then msg("Leave combat first."); return end
        db.hideTriggers = value == "off"
        if applyTriggers() then ReloadUI() else msg("ConsolePort bar isn't loaded; the change applies next time it loads.") end
    elseif cmd == "trail" and (value == "on" or value == "off") then
        db.hideTrail = value == "off"
        applyTrail()
        msg("Controller icon next to the pointer " .. onoff(not db.hideTrail))
    elseif cmd == "calibrate" then
        if applyCalibration() then msg("Controller calibration restored.") end
        reloadConsolePortBindings()
    else
        msg("Hide default pet bar " .. onoff(db.petbar) .. " | L2/R2 icons " .. onoff(not db.hideTriggers) ..
            " | XP bar lift " .. (db.xpLift or 0))
        msg("/thor xp <number>  - move the XP bar up (e.g. 10, 20; 0 = original)")
        msg("/thor petbar on|off  - hide Blizzard's default pet bar")
        msg("/thor triggers on|off  - show the L2/R2 icons on the ConsolePort bar (reloads)")
        msg("/thor trail on|off  - controller icon next to the pointer")
        msg("/thor calibrate  - put the controller calibration back")
    end
end
