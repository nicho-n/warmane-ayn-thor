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
--  4. XP bar: lifts ConsolePort's XP/reputation bar off the screen edge; a right-click on it
--     opens Warmane's XP rate menu (which sits on WoW's own, hidden, XP bar).
--  5. Triggers: hides the big L2/R2 icons on the ConsolePort bar (toggleable).
--  6. Cursor trail: hides ConsolePort's controller icon next to the pointer.
--  8. Cast bar: WoW's own cast bar instead of ConsolePortBar's restyled one.
--  9. GCD sweep: skips the 1.5 s cooldown animation on every button (performance).
--  7. Default bindings: characters without ConsolePort bindings get the layout in
--     DEFAULT_BINDINGS (Select targets a friend, L1 an enemy, Start interacts with
--     the unit under the pointer; see README).
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
    skipGuideBtn = true, skipCalibration = true,
    skipCP_T3 = true, skipCP_T4 = true, skipCP_T5 = true, skipCP_T6 = true,
    disableStickMouse = true,
    -- The Thor's face buttons are labelled like a Switch controller (top X, right A,
    -- bottom B, left Y). Only changes ConsolePort's button pictures; bindings stay
    -- on button positions.
    type = "SWITCH",
}
-- The rest of the Thor layout, for an account whose ConsolePort settings were just created
-- (see freshSetup): L2/R2 are the modifiers (Shift/Ctrl), L1/R1 plain buttons. The app
-- writes the same settings before the first login when it knows the account name.
local FRESH_SETTINGS = {
    CP_M1 = "CP_TL2", CP_M2 = "CP_TR2", CP_T1 = "CP_TL1", CP_T2 = "CP_TR1",
    actionBarStyle = 4, bindView = 1, autoLootDefault = true, autoSellJunk = true,
    autoExtra = true, stickRadialType = 2, disableSmartMouse = true, disableKeyboard = true,
    disableCvarReset = true, UIdropDownFix = true,
}
-- Set when ConsolePort made new settings on this login (and opened its setup screen):
-- they're replaced with the Thor layout, which takes effect after a UI reload. WoW only
-- lets an addon reload the UI from a button press, so a popup asks for it.
local freshSetup

StaticPopupDialogs["THORCONTROLS_RELOAD"] = {
    text = "Thor Controls: one-time controller setup for this account. Reload the UI to finish.\n\nNameplates must be on for bottom screen targeting to work.",
    button1 = RELOADUI or "Reload UI",
    OnAccept = function() ReloadUI() end,
    timeout = 0,
    whileDead = true,
    hideOnEscape = false,
}
local MODIFIERS = { "", "SHIFT-", "CTRL-", "CTRL-SHIFT-" }

-- Default controller bindings for characters that have none yet (see README).
-- Per button: no modifier, L2 (SHIFT-), R2 (CTRL-), L2+R2 (CTRL-SHIFT-).
local DEFAULT_BINDINGS = {
    CP_R_LEFT  = { [""] = "ACTIONBUTTON1", ["SHIFT-"] = "ACTIONBUTTON6", ["CTRL-"] = "MULTIACTIONBAR1BUTTON1", ["CTRL-SHIFT-"] = "MULTIACTIONBAR1BUTTON6" },  -- X
    CP_R_UP    = { [""] = "ACTIONBUTTON2", ["SHIFT-"] = "ACTIONBUTTON7", ["CTRL-"] = "MULTIACTIONBAR1BUTTON2", ["CTRL-SHIFT-"] = "MULTIACTIONBAR1BUTTON7" },  -- Y
    CP_R_RIGHT = { [""] = "ACTIONBUTTON3", ["SHIFT-"] = "ACTIONBUTTON8", ["CTRL-"] = "MULTIACTIONBAR1BUTTON3", ["CTRL-SHIFT-"] = "OPENALLBAGS" },             -- B
    CP_R_DOWN  = { [""] = "ACTIONBUTTON5", ["SHIFT-"] = "ACTIONBUTTON9", ["CTRL-"] = "MULTIACTIONBAR4BUTTON5", ["CTRL-SHIFT-"] = "CLICK ConsolePortUtilityToggle:LeftButton" }, -- A
    CP_T1      = { [""] = "TARGETNEARESTENEMY", ["SHIFT-"] = "MULTIACTIONBAR1BUTTON4", ["CTRL-"] = "MINIMAPZOOMIN", ["CTRL-SHIFT-"] = "MULTIACTIONBAR1BUTTON9" },  -- L1
    CP_T2      = { [""] = "JUMP", ["SHIFT-"] = "ACTIONBUTTON10", ["CTRL-"] = "MULTIACTIONBAR1BUTTON5", ["CTRL-SHIFT-"] = "MULTIACTIONBAR1BUTTON10" },         -- R1
    CP_L_UP    = { [""] = "MULTIACTIONBAR1BUTTON12", ["SHIFT-"] = "CAMERAZOOMIN", ["CTRL-"] = "MULTIACTIONBAR2BUTTON6", ["CTRL-SHIFT-"] = "MULTIACTIONBAR2BUTTON10" },
    CP_L_DOWN  = { [""] = "ACTIONBUTTON11", ["SHIFT-"] = "CAMERAZOOMOUT", ["CTRL-"] = "MULTIACTIONBAR2BUTTON8", ["CTRL-SHIFT-"] = "MULTIACTIONBAR2BUTTON12" },
    CP_L_LEFT  = { [""] = "MULTIACTIONBAR1BUTTON11", ["SHIFT-"] = "MULTIACTIONBAR2BUTTON1", ["CTRL-"] = "MULTIACTIONBAR2BUTTON5", ["CTRL-SHIFT-"] = "MULTIACTIONBAR2BUTTON9" },
    CP_L_RIGHT = { [""] = "ACTIONBUTTON12", ["SHIFT-"] = "MULTIACTIONBAR2BUTTON3", ["CTRL-"] = "MULTIACTIONBAR2BUTTON7", ["CTRL-SHIFT-"] = "MULTIACTIONBAR2BUTTON11" },
    CP_X_LEFT  = { [""] = "TARGETNEARESTFRIEND", ["SHIFT-"] = "TOGGLEWORLDMAP", ["CTRL-"] = "CP_CAMZOOMOUT", ["CTRL-SHIFT-"] = "CP_CAMZOOMIN" },        -- Select
    CP_X_RIGHT = { [""] = "INTERACTMOUSEOVER", ["SHIFT-"] = "TOGGLEGAMEMENU", ["CTRL-"] = "CLICK ConsolePortRaidCursorToggle:LeftButton", ["CTRL-SHIFT-"] = "OPENCHAT" }, -- Start
    CP_T_L3    = {},
    CP_T_R3    = {},
}

local function copy(t)
    local out = {}
    for k, v in pairs(t) do out[k] = type(v) == "table" and copy(v) or v end
    return out
end

-- ConsolePortLK keys a character's binding set by class number (its "spec" on 3.3.5).
local CLASS_IDS = { WARRIOR = 1, PALADIN = 2, HUNTER = 3, ROGUE = 4, PRIEST = 5, DEATHKNIGHT = 6,
                    SHAMAN = 7, MAGE = 8, WARLOCK = 9, DRUID = 10 }

local function classSetId()
    local _, class = UnitClass("player")
    return CLASS_IDS[class or ""] or 1
end

-- Gives this character the default bindings if it has none. ConsolePort reads the set
-- at login (after this addon loads), so nothing else is needed.
local function applyDefaultBindings(force)
    if type(ConsolePortBindingSet) ~= "table" then ConsolePortBindingSet = {} end
    local id = classSetId()
    local set = ConsolePortBindingSet[id]
    if force or type(set) ~= "table" or not next(set) then
        ConsolePortBindingSet[id] = copy(DEFAULT_BINDINGS)
        return true
    end
    return false
end

-- Once per character: D-pad right used to be run/walk; it's now action bar slot 12,
-- and run/walk isn't bound to any controller button.
local function migrateRunWalk()
    local set = type(ConsolePortBindingSet) == "table" and ConsolePortBindingSet[classSetId()]
    if type(set) ~= "table" then return end
    for button, mods in pairs(set) do
        if type(mods) == "table" then
            for mod, action in pairs(mods) do
                if action == "TOGGLERUN" then
                    mods[mod] = (button == "CP_L_RIGHT" and mod == "") and "ACTIONBUTTON12" or nil
                end
            end
        end
    end
end

local defaults = { petbar = true, xpShow = true, xpText = false, xpWidth = 100, xpHeight = 11, xpX = 0, xpY = 0, hideTriggers = true, hideTrail = true, defaultCastBar = true, hideGcd = false, petRingLeft = true, petRingScale = 80, bagSort = false, mapArrowScale = 150 }
local db
local pending = {}
local events = CreateFrame("Frame")
local owner = CreateFrame("Frame", "ThorControlsBindingOwner", UIParent)

local function msg(text)
    DEFAULT_CHAT_FRAME:AddMessage("|cff69ccf0Thor Controls:|r " .. text)
end

-- Measurement output also goes into ThorControlsDB.log, which WoW writes to
-- WTF/Account/<account>/SavedVariables/ThorControls.lua on /reload or logout.
local LOG_MAX = 400
local function report(text)
    msg(text)
    if not db then return end
    db.log = db.log or {}
    db.log[#db.log + 1] = date("%H:%M:%S ") .. text:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", "")
    while #db.log > LOG_MAX do table.remove(db.log, 1) end
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

-- Nameplates: the app's touch targeting finds units through their nameplates, so friendly
-- and enemy nameplates are switched on once per character (WoW keeps them per character;
-- turning them off later with the V keys sticks).
local function applyNameplates()
    if not db then return end
    db.nameplatesOn = db.nameplatesOn or {}
    local who = (UnitName("player") or "?") .. "-" .. (GetRealmName() or "?")
    if db.nameplatesOn[who] then return end
    if InCombatLockdown() then pending.nameplates = true; return end
    pending.nameplates = nil
    SetCVar("nameplateShowEnemies", "1")
    SetCVar("nameplateShowFriends", "1")
    db.nameplatesOn[who] = true
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
        -- The app's camera rotate mode (R3): turns the camera only, like a left-button drag.
        SetOverrideBinding(owner, true, mod .. "NUMPADMULTIPLY", "CAMERAORSELECTORMOVE")
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
-- 4. XP bar: shorter, framed, centred on the bottom edge of the screen. Text, width,
--    height and offsets come from the app's settings (AppSettings.lua, or /thor xpbar
--    typed by the app when they change in game).
---------------------------------------------------------------------------

-- XP fill: bright blue while rested (#0063FF), violet (#9400D3) once rested runs out, on
-- WoW's plain status bar texture (ConsolePort's is very dark). The rest of the rested bonus
-- is dark blue (#002466) from the end of the fill to the tick; ConsolePort's own overlay for
-- it is made invisible, as it sits above the fill and would cover it.
local XP_RESTED, XP_NORMAL = { 0, 0.39, 1 }, { 0.58, 0, 0.83 }
local function colorXpBar(bar)
    if not bar.ExhaustionTick or not bar.SetBarColorRaw then return end
    local c = GetRestState() == 1 and XP_RESTED or XP_NORMAL
    bar:SetBarColorRaw(c[1], c[2], c[3], 1)
    local fill = bar.ExhaustionLevelFillBar
    if fill then
        bar.thorColoring = true
        fill:SetVertexColor(0, 0, 0, 0)
        bar.thorColoring = nil
    end
end

-- A 2px border and a dark track behind the bar, so its start and end are plain.
local function frameBar(bar)
    if bar.thorTrack then return end
    local function tex(layer, owner)
        local t = (owner or bar):CreateTexture(nil, layer)
        t:SetTexture("Interface\\Buttons\\WHITE8X8")
        return t
    end
    -- On the StatusBar itself: it's in a lower strata than the bar, so a track on the bar
    -- would cover the fill.
    bar.thorTrack = tex("BACKGROUND", bar.StatusBar)
    bar.thorTrack:SetAllPoints(bar.StatusBar or bar)
    bar.thorTrack:SetVertexColor(0, 0, 0, 0.65)
    local b = 2
    -- Each edge: two anchors just outside the bar, plus its thickness.
    local edges = {
        { "BOTTOMLEFT", "TOPLEFT", -b, 0, "BOTTOMRIGHT", "TOPRIGHT", b, 0, "h" },
        { "TOPLEFT", "BOTTOMLEFT", -b, 0, "TOPRIGHT", "BOTTOMRIGHT", b, 0, "h" },
        { "TOPRIGHT", "TOPLEFT", 0, 0, "BOTTOMRIGHT", "BOTTOMLEFT", 0, 0, "w" },
        { "TOPLEFT", "TOPRIGHT", 0, 0, "BOTTOMLEFT", "BOTTOMRIGHT", 0, 0, "w" },
    }
    for _, e in ipairs(edges) do
        local t = tex("OVERLAY")
        t:SetPoint(e[1], bar, e[2], e[3], e[4])
        t:SetPoint(e[5], bar, e[6], e[7], e[8])
        if e[9] == "h" then t:SetHeight(b) else t:SetWidth(b) end
        t:SetVertexColor(0, 0, 0, 1)
    end
    if bar.ExhaustionTick and bar.SetBarColorRaw then
        if bar.StatusBar and bar.StatusBar.BarTexture then
            bar.StatusBar.BarTexture:SetTexture("Interface\\TargetingFrame\\UI-StatusBar")
        end
        -- Runs after ConsolePort's own color hook.
        hooksecurefunc(bar, "SetBarColor", colorXpBar)
        bar:HookScript("OnShow", colorXpBar)
        if bar.ExhaustionLevelFillBar then
            hooksecurefunc(bar.ExhaustionLevelFillBar, "SetVertexColor", function()
                if not bar.thorColoring then colorXpBar(bar) end
            end)
        end
        local watch = CreateFrame("Frame")
        watch:RegisterEvent("PLAYER_UPDATE_RESTING")
        watch:RegisterEvent("UPDATE_EXHAUSTION")
        watch:RegisterEvent("PLAYER_ENTERING_WORLD")
        watch:SetScript("OnEvent", function() colorXpBar(bar) end)
    end
    colorXpBar(bar)
    local sb = bar.StatusBar
    -- Remaining rested XP: from the end of the fill to where rested ends.
    if sb and bar.ExhaustionTick then
        local rested = tex("ARTWORK", sb)
        rested:SetVertexColor(0, 0.14, 0.4, 1)
        local function placeRested()
            local cur, max, rest = UnitXP("player"), UnitXPMax("player"), GetXPExhaustion()
            local w = sb:GetWidth()
            if not rest or rest <= 0 or not max or max <= 0 or not w or w <= 0 then rested:Hide() return end
            local left = w * cur / max
            local right = w * min(1, (cur + rest) / max)
            if right - left < 1 then rested:Hide() return end
            rested:ClearAllPoints()
            rested:SetPoint("TOPLEFT", sb, "TOPLEFT", left, 0)
            rested:SetPoint("BOTTOMRIGHT", sb, "BOTTOMLEFT", right, 0)
            rested:Show()
        end
        local watch = CreateFrame("Frame")
        for _, e in ipairs({ "PLAYER_XP_UPDATE", "UPDATE_EXHAUSTION", "PLAYER_LEVEL_UP", "PLAYER_ENTERING_WORLD" }) do
            watch:RegisterEvent(e)
        end
        watch:SetScript("OnEvent", placeRested)
        sb:HookScript("OnSizeChanged", placeRested)
        placeRested()
    end
    -- A thin line at every 5%, over the fill.
    if sb then
        local lines = {}
        for i = 1, 19 do
            local t = tex("OVERLAY", sb)
            t:SetVertexColor(0, 0, 0, 0.85)
            lines[i] = t
        end
        -- Each line exactly 2 screen pixels wide, on whole pixels, so they all look the same
        -- (UI units don't fall on whole pixels).
        local function place()
            local w, left = sb:GetWidth(), sb:GetLeft()
            if not w or not left then return end
            local physH = tonumber(strmatch(GetCVar("gxResolution") or "", "%d+x(%d+)")) or 1080
            local px = 768 / physH / sb:GetEffectiveScale()
            for i, t in ipairs(lines) do
                local x = floor((left + w * i / 20) / px + 0.5) * px - left
                t:ClearAllPoints()
                t:SetWidth(2 * px)
                t:SetPoint("TOPLEFT", sb, "TOPLEFT", x, 0)
                t:SetPoint("BOTTOMLEFT", sb, "BOTTOMLEFT", x, 0)
            end
        end
        bar.thorPlaceLines = place
        sb:HookScript("OnSizeChanged", place)
        place()
    end
    -- Text 3 points bigger.
    local text = bar.OverlayFrame and bar.OverlayFrame.Text
    if text and text.GetFont then
        local font, size, flags = text:GetFont()
        if font and size then text:SetFont(font, size + 3, flags) end
        -- Shown or hidden by /thor xptext, whatever ConsolePort does on hover.
        hooksecurefunc(text, "Show", function(t) if not (db and db.xpText) then t:Hide() end end)
        hooksecurefunc(text, "Hide", function(t) if db and db.xpText then t:Show() end end)
        if db and db.xpText then text:Show() else text:Hide() end
    end
end

-- Width: db.xpWidth % of the span from the middle of D-pad Left (Down with text) to the middle of A,
-- centered on the screen rather than on the ConsolePort bar.
-- Bar bottom above UIParent's bottom: the 2px border plus the few pixels of the game window
-- that sit below the edge of the Thor's screen.
local XP_BOTTOM = 5.5

local function xpTargetWidth(scale)
    local pct = db and db.xpWidth or 100
    -- 100%, without text: from the middle of D-pad Left to the middle of A (the right face
    -- button). With text: from D-pad Down to A.
    local left, right = (db and db.xpText) and _G.CPB_L_DOWN or _G.CPB_L_LEFT, _G.CPB_R_RIGHT
    local lx, rx = left and left:GetCenter(), right and right:GetCenter()
    if not lx or not rx then return nil end
    -- + ConsolePort's end caps, which SetSingleBarSize takes off again.
    return ((rx * right:GetEffectiveScale() - lx * left:GetEffectiveScale()) / scale) * pct / 100 + 8
end

local function shortenBar(container, bar, width, isDouble)
    if not bar or bar:GetNumPoints() == 0 then return end
    local scale = bar:GetEffectiveScale()
    local target = xpTargetWidth(scale)
    if target and target > 50 then
        if isDouble then container:SetDoubleBarSize(bar, target) else container:SetSingleBarSize(bar, target) end
    end
    -- A single bar: db.xpHeight, at least 16 with text (bigger font). Two stacked bars
    -- keep ConsolePort's size.
    if not isDouble and bar.StatusBar then
        local h = db and db.xpHeight or 11
        if db and db.xpText then h = max(h, 16) end
        bar:SetHeight(h)
        bar.StatusBar:SetHeight(h)
    end
    -- On the bottom edge of the screen, centred: the 2px border just touches the edge.
    -- With two bars the second sits on top of the first.
    local _, _, _, _, y = bar:GetPoint(1)
    local stackY = (isDouble and (y or 0) > 5) and (bar:GetHeight() + 2) or 0
    bar:ClearAllPoints()
    bar:SetPoint("BOTTOM", UIParent, "BOTTOM", db and db.xpX or 0, XP_BOTTOM + stackY + (db and db.xpY or 0))
    frameBar(bar)
    if bar.thorPlaceLines then bar.thorPlaceLines() end
    -- Turned off in the app: ConsolePort has just shown it; hide it again.
    if db and db.xpShow == false then bar:Hide() end
end

local function relayoutWatchBars()
    local container = ConsolePortBarWatchBars
    if container and container.UpdateBarsShown then container:UpdateBarsShown() end
end

-- Warmane's XP rate menu (x0.5 / x1 / x3 / x5 / x7) opens on a right-click on WoW's own XP
-- bar, MainMenuExpBar, which section 11 hides; ConsolePort's XP bar is the one on screen. A
-- right-click on it runs whatever mouse handler Warmane has put on MainMenuExpBar, looked up
-- at click time.
local function openServerXpMenu()
    local blizz = MainMenuExpBar
    if blizz then
        for _, script in ipairs({ "OnMouseUp", "OnMouseDown", "OnClick" }) do
            local handler = blizz:HasScript(script) and blizz:GetScript(script)
            if handler then
                handler(blizz, "RightButton")
                return
            end
        end
    end
    msg("This realm has no XP rate menu on the XP bar.")
end

local function hookXpRightClick(container)
    for _, bar in ipairs(container.bars or {}) do
        if not bar.thorXpClick and CPExpBarMixin and bar.UpdateTick == CPExpBarMixin.UpdateTick then
            bar.thorXpClick = true
            bar:EnableMouse(true)
            bar:HookScript("OnMouseUp", function(_, button)
                if button == "RightButton" then openServerXpMenu() end
            end)
        end
    end
end

local function hookWatchBars()
    local container = ConsolePortBarWatchBars
    if not container or container.thorHooked or not container.LayoutBar then return end
    container.thorHooked = true
    -- ConsolePort's fading side backgrounds blur where the bar starts and ends.
    if container.BGLeft then container.BGLeft:Hide() end
    if container.BGRight then container.BGRight:Hide() end
    -- ConsolePort's decorative line across its bar runs right through the XP bar.
    local line = container:GetParent() and container:GetParent().BottomLine
    if line then
        line:Hide()
        hooksecurefunc(line, "Show", line.Hide)
    end
    hooksecurefunc(container, "LayoutBar", function(self, bar, width, isTopBar, isDouble)
        shortenBar(self, bar, width, isDouble)
    end)
    hookXpRightClick(container)
    relayoutWatchBars()
end

---------------------------------------------------------------------------
-- 5. L2/R2 trigger icons (CP_T3/CP_T4 placeholders on the ConsolePort bar).
---------------------------------------------------------------------------
local TRIGGER_LAYOUT = {
    CP_T3 = { point = { "LEFT", 456, 56 }, dir = "right", size = 64 },
    CP_T4 = { point = { "RIGHT", -456, 56 }, dir = "left", size = 64 },
}

-- A new character's bar is built with ConsolePortBar's default layout, L2/R2 included,
-- before this addon loads; set when they're taken out of it, so they're hidden at login
-- (from the next login they're not built at all).
local triggersRemoved

local function hideTriggerButtons()
    for id in pairs(TRIGGER_LAYOUT) do
        local base = "CPB_" .. id:sub(4)
        for _, suffix in ipairs({ "", "_SHIFT", "_CTRL", "_CTRL-SHIFT", "_SHADOW" }) do
            local f = _G[base .. suffix]
            if f then
                f:SetAlpha(0)
                f:Hide()
            end
        end
    end
end

local function applyTriggers()
    local cfg = ConsolePortBarSetup
    if not db or not cfg or not cfg.layout then return false end
    -- WoW's own cast bar instead of ConsolePortBar's restyled one in the middle of its bar.
    cfg.defaultCastBar = db.defaultCastBar and true or nil
    for id, geometry in pairs(TRIGGER_LAYOUT) do
        if db.hideTriggers then
            if cfg.layout[id] then triggersRemoved = true end
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
-- 9. Global cooldown sweep: every spell, instant or not, starts a 1.5 s cooldown
-- animation on every action button at once. Shown by default (section 13 makes it
-- cheap on ConsolePort's bar); /thor gcd off skips sweeps that short.
---------------------------------------------------------------------------
local GCD_MAX = 1.5

hooksecurefunc("CooldownFrame_SetTimer", function(self, start, duration, enable)
    if db and db.hideGcd and enable and enable ~= 0 and duration and duration > 0 and duration <= GCD_MAX then
        -- Keep a real cooldown that is still running (section 13 tracks it).
        if self.thorEnd and self.thorEnd > GetTime() then return end
        self:Hide()
    end
end)

---------------------------------------------------------------------------
-- 11. Default action bars: hidden while ConsolePort's bar is in use. Newer
-- ConsolePortBar versions do this themselves; 1.0.4 doesn't. Same steps as
-- ConsolePortBar 1.5's Custom/Blizzard.lua (from Bartender4). Out of combat only.
---------------------------------------------------------------------------
local blizzHider = CreateFrame("Frame")
blizzHider:Hide()

local function hideBlizzardBars()
    if not IsAddOnLoaded("ConsolePortBar") then return end
    if MainMenuBarArtFrame:GetParent() ~= MainMenuBar then return end -- already done
    if InCombatLockdown() then pending.blizz = true; return end
    pending.blizz = nil
    for _, bar in pairs({ MainMenuBarArtFrame, MainMenuBarMaxLevelBar, VehicleMenuBar, BonusActionBarFrame,
                          MultiCastActionBarFrame, MultiBarLeft, MultiBarRight, MultiBarBottomLeft, MultiBarBottomRight }) do
        bar:SetParent(blizzHider)
    end
    MainMenuBarArtFrame:Hide()
    for _, n in pairs({ "ActionButton", "BonusActionButton", "MultiBarLeftButton", "MultiBarRightButton",
                        "MultiBarBottomLeftButton", "MultiBarBottomRightButton" }) do
        for i = 1, 12 do
            local b = _G[n .. i]
            if b then
                b:Hide()
                b:UnregisterAllEvents()
                b:SetAttribute("statehidden", true)
            end
        end
    end
    for _, k in pairs({ "MainMenuBar", "BonusActionBar", "ShapeshiftBarFrame", "PossessBarFrame", "PETACTIONBAR_YPOS" }) do
        UIPARENT_MANAGED_FRAME_POSITIONS[k] = nil
    end
    MainMenuBar:EnableMouse(false)
    for _, f in pairs({ MainMenuExpBar, ExhaustionTick, MainMenuBarPerformanceBar, ReputationWatchBar }) do
        f:SetParent(blizzHider)
    end
    for _, bar in pairs({ ShapeshiftBarFrame, PossessBarFrame }) do
        bar:UnregisterAllEvents()
        bar:SetParent(blizzHider)
        bar:Hide()
    end
end

---------------------------------------------------------------------------
-- 12. Utility ring 3D model: ConsolePort 1.5.0 creates a transparent PlayerModel
-- (ConsolePortUtilityAnimation.Spell) that is shown from load until the ring is
-- first opened, and nothing ever puts a model in it. Keep it hidden: a shown
-- model frame makes the engine do work every frame that no addon profiler sees.
---------------------------------------------------------------------------
local setSpikes -- section 10

---------------------------------------------------------------------------
-- 13. Cooldown sweeps on ConsolePort bar buttons. Each button has a native
-- Cooldown frame that ConsolePort 1.5.0 keeps invisible on round buttons (it draws
-- its own sweep from a SetCooldown hook). Starting the native one on every button
-- still costs the engine about half a second per cast under emulation, so on
-- round buttons only ConsolePort's own sweep is started. Square buttons, where
-- the native sweep is the visible one, keep it. Global cooldowns (hidden by
-- section 9 anyway) start neither.
---------------------------------------------------------------------------
-- ConsolePort 1.5.0's cooldown text (CPCC) is keyed by frame name, but the round
-- sweep passes the frame itself: OnUpdate then errors on every frame while a
-- cooldown runs (Wrapper.lua:618), which drags the framerate down. Accept both.
local function fixCooldownText(cpcc)
    if not cpcc or cpcc.thorFixed then return end
    cpcc.thorFixed = true
    local function key(target)
        if type(target) == "table" and target.GetName then return target:GetName() end
        return target
    end
    local onUpdate, start, stop = cpcc.OnUpdate, cpcc.StartCooldown, cpcc.StopCooldown
    -- Exact time instead of ConsolePort's rounded "2m": m:ss from a minute up,
    -- whole seconds (rounded up, like the game) below, tenths under 10 s.
    local function exact(remaining)
        if remaining >= 60 then
            local s = math.ceil(remaining)
            return string.format("%d:%02d", math.floor(s / 60), s % 60)
        elseif remaining >= 10 then
            return string.format("%d", math.ceil(remaining))
        end
        return string.format("%.1f", remaining)
    end
    cpcc.OnUpdate = function(self, target, elapsed)
        target = key(target)
        local holder, meta = target and self.texts[target], target and self.meta[target]
        if not holder or not meta then return end
        onUpdate(self, target, elapsed or 0)
        if meta.visible and holder.fontstring then
            local remaining = meta.start + meta.duration - GetTime()
            if remaining > 0 then holder.fontstring:SetText(exact(remaining)) end
        end
    end
    cpcc.StartCooldown = function(self, target, ...)
        return start(self, key(target), ...)
    end
    cpcc.StopCooldown = function(self, target, ...)
        local k = key(target)
        return stop(self, k, ...)
    end
    -- The text was only updated from the sweep animations, so it could freeze
    -- (e.g. "9.1" left after the cooldown ended). Update every text 10 times a
    -- second here, and clear it when its cooldown is over.
    local ticker, wait = CreateFrame("Frame"), 0
    ticker:SetScript("OnUpdate", function(_, elapsed)
        wait = wait - elapsed
        if wait > 0 then return end
        wait = 0.1
        local now = GetTime()
        for name, meta in pairs(cpcc.meta) do
            local holder = cpcc.texts[name]
            if meta.visible then
                if now >= meta.start + meta.duration then
                    stop(cpcc, name)
                    if holder then holder:Hide() end
                else
                    if holder and not holder:IsShown() then holder:Show() end
                    cpcc:OnUpdate(name, 0.1)
                end
            elseif holder and holder:IsShown() then
                holder:Hide()
            end
        end
    end)
end

local function fixBarCooldowns()
    local cpapi = ConsolePort and ConsolePort.GetData and ConsolePort:GetData().CPAPI
    local hook = cpapi and cpapi.RoundCooldown_OnSetCooldown
    if not hook then return end
    fixCooldownText(cpapi.CPCC)
    local f = EnumerateFrames()
    while f do
        local name = f:GetName()
        local cd = name and name:match("^CPB_") and f.cooldown
        if cd and not cd.thorLight and cd:GetObjectType() == "Cooldown" then
            cd.thorLight = true
            -- The round sweep is drawn inside a ScrollFrame, which is only redrawn when
            -- its scroll child changes size/position. The native cooldown used to force
            -- that every frame; now refresh it whenever the sweep moves.
            local spinner = f.roundcd and f.roundcd.spinner
            local sf = spinner and spinner._scrollframe
            if spinner and sf and spinner.SetValue and not spinner.thorRefresh then
                spinner.thorRefresh = true
                local setValue = spinner.SetValue
                spinner.SetValue = function(self, value)
                    local last = self._lastValue
                    setValue(self, value)
                    if self._lastValue ~= last then sf:UpdateScrollChildRect() end
                end
            end
            -- Without a native cooldown running, the Cooldown frame hides itself at
            -- once, and ConsolePort's OnHide then clears its own sweep and timer. Only
            -- let that through when the button's spell really is off cooldown.
            local onHide = cd:GetScript("OnHide")
            cd:SetScript("OnHide", function(self, ...)
                local now = GetTime()
                local button = self:GetParent()
                if button and button.GetCooldown then
                    local s, d = button:GetCooldown()
                    if s and d and d > GCD_MAX and s + d > now + 0.05 then return end
                end
                if onHide then return onHide(self, ...) end
            end)
            local native = getmetatable(cd).__index.SetCooldown
            cd.SetCooldown = function(self, start, duration, ...)
                -- Global cooldown: no sweep work (section 9 hides it, unless a real
                -- cooldown is still running on this button).
                if db and db.hideGcd and duration and duration > 0 and duration <= GCD_MAX then return end
                -- Same cooldown again (events repeat it): leave the running sweep alone.
                if self.thorStart == start and self.thorEnd and self.thorEnd > GetTime() then return end
                self.thorStart, self.thorEnd = start, (start or 0) + (duration or 0)
                local button = self:GetParent()
                if button and button.isSquareMode then native(self, start, duration, ...) end
                hook(self, start, duration)
            end
        end
        f = EnumerateFrames(f)
    end
end

-- 14. Battlegrounds key for the app's Interface page: Ctrl+Alt+Shift+V opens the PvP
-- window on its Battlegrounds tab (H opens it on the PvP tab), or closes it.
local bgButton = CreateFrame("Button", "ThorControlsBattlegrounds", UIParent)
bgButton:SetScript("OnClick", function()
    local frame, tab = _G.PVPParentFrame, _G.PVPParentFrameTab2
    if not frame then TogglePVPFrame(); return end
    if frame:IsShown() and PanelTemplates_GetSelectedTab and PanelTemplates_GetSelectedTab(frame) == 2 then
        HideUIPanel(frame)
        return
    end
    if not frame:IsShown() then TogglePVPFrame() end
    if tab and frame:IsShown() then tab:Click() end
end)
-- Alt+Shift+L (the app's Leave Party button): leaves the party or raid.
local leaveButton = CreateFrame("Button", "ThorControlsLeaveParty", UIParent)
leaveButton:SetScript("OnClick", function()
    if GetNumPartyMembers() > 0 or GetNumRaidMembers() > 0 then LeaveParty() end
end)
-- Alt+Ctrl+F5 / Alt+Ctrl+F6 (the app's Enemy / Friendly nameplates buttons): each turns
-- one kind of nameplate on or off. WoW's own V / Shift+V keep their on/off state apart
-- from the settings, so they get out of step with each other.
local function toggleNameplates(cvar)
    SetCVar(cvar, GetCVar(cvar) == "1" and "0" or "1")
    -- Keep WoW's own V / Shift+V toggles in step.
    NAMEPLATES_ON = GetCVar("nameplateShowEnemies") == "1"
    FRIENDNAMEPLATES_ON = GetCVar("nameplateShowFriends") == "1"
end
-- Alt+Ctrl+F7 / Alt+Ctrl+F8 (the app's R3 camera rotate mode): saves the camera view when
-- it starts and puts it back when it ends, so the camera returns behind the character.
local CAMERA_VIEW = 5
-- While the mode is on, WoW's camera following (which swings the camera back behind a
-- character moving forward) is off; the player's setting is kept in ThorControlsDB until the
-- mode ends (Alt+Ctrl+F8, or Alt+Ctrl+F11 when the app ends it without moving the camera), and
-- put back at login if the game stopped while it was on.
local FOLLOW_CVARS = { "cameraSmoothStyle", "cameraSmoothTrackingStyle" }
local function followOff()
    if not db then return end
    if not db.cameraFollow then
        db.cameraFollow = {}
        for _, name in ipairs(FOLLOW_CVARS) do db.cameraFollow[name] = GetCVar(name) end
    end
    for _, name in ipairs(FOLLOW_CVARS) do SetCVar(name, "0") end
end
local function followBack()
    if not db or not db.cameraFollow then return end
    for name, value in pairs(db.cameraFollow) do SetCVar(name, value) end
    db.cameraFollow = nil
end
local orbitStart = CreateFrame("Button", "ThorControlsCameraSave", UIParent)
orbitStart:SetScript("OnClick", function()
    SaveView(CAMERA_VIEW)
    followOff()
end)
local orbitQuit = CreateFrame("Button", "ThorControlsCameraFollow", UIParent)
orbitQuit:SetScript("OnClick", followBack)
-- Put back at once, without WoW's camera move animation (cameraViewBlendStyle 2 = instant).
-- While the character moves, one SetView can land only part of the way, so it is repeated
-- every frame for a moment; then the player's own blend setting goes back.
local RESTORE_TIME = 0.2
local orbitEnd = CreateFrame("Button", "ThorControlsCameraRestore", UIParent)
local restoreBlend, restoreLeft
local function restoreView()
    if not restoreLeft then restoreBlend = GetCVar("cameraViewBlendStyle") end
    restoreLeft = RESTORE_TIME
    SetCVar("cameraViewBlendStyle", "2")
    SetView(CAMERA_VIEW)
    orbitEnd:SetScript("OnUpdate", function(f, elapsed)
        restoreLeft = restoreLeft - elapsed
        SetView(CAMERA_VIEW)
        if restoreLeft <= 0 then
            f:SetScript("OnUpdate", nil)
            restoreLeft = nil
            SetCVar("cameraViewBlendStyle", restoreBlend)
        end
    end)
end
orbitEnd:SetScript("OnClick", function()
    followBack()
    restoreView()
end)
-- Alt+Ctrl+F9 / Alt+Ctrl+F10 (the app's turn around, left stick pulled fully back): the app
-- turns the character with a fixed mouse movement, measured with this sensitivity. It is
-- used just for the turn; then the player's own comes back.
local TURN_CVARS = { mouseSpeed = "0.7" }
local turnSaved
local turnStart = CreateFrame("Button", "ThorControlsTurnStart", UIParent)
turnStart:SetScript("OnClick", function()
    if not turnSaved then
        turnSaved = {}
        for name in pairs(TURN_CVARS) do turnSaved[name] = GetCVar(name) end
    end
    for name, value in pairs(TURN_CVARS) do SetCVar(name, value) end
end)
local turnEnd = CreateFrame("Button", "ThorControlsTurnEnd", UIParent)
turnEnd:SetScript("OnClick", function()
    if not turnSaved then return end
    for name, value in pairs(turnSaved) do SetCVar(name, value) end
    turnSaved = nil
end)
local enemyPlates = CreateFrame("Button", "ThorControlsEnemyNameplates", UIParent)
enemyPlates:SetScript("OnClick", function() toggleNameplates("nameplateShowEnemies") end)
local friendPlates = CreateFrame("Button", "ThorControlsFriendlyNameplates", UIParent)
friendPlates:SetScript("OnClick", function() toggleNameplates("nameplateShowFriends") end)
local function bindBattlegroundsKey()
    if InCombatLockdown() then return end
    SetOverrideBindingClick(bgButton, false, "ALT-CTRL-SHIFT-V", "ThorControlsBattlegrounds")
    SetOverrideBindingClick(leaveButton, false, "ALT-SHIFT-L", "ThorControlsLeaveParty")
    SetOverrideBindingClick(enemyPlates, false, "ALT-CTRL-F5", "ThorControlsEnemyNameplates")
    SetOverrideBindingClick(friendPlates, false, "ALT-CTRL-F6", "ThorControlsFriendlyNameplates")
    SetOverrideBindingClick(orbitStart, false, "ALT-CTRL-F7", "ThorControlsCameraSave")
    SetOverrideBindingClick(orbitEnd, false, "ALT-CTRL-F8", "ThorControlsCameraRestore")
    SetOverrideBindingClick(orbitQuit, false, "ALT-CTRL-F11", "ThorControlsCameraFollow")
    SetOverrideBindingClick(turnStart, false, "ALT-CTRL-F9", "ThorControlsTurnStart")
    SetOverrideBindingClick(turnEnd, false, "ALT-CTRL-F10", "ThorControlsTurnEnd")
end

local function noop() end
local function hideUtilityModel()
    local anim = _G.ConsolePortUtilityAnimation
    if anim and not anim:IsProtected() then
        anim:Hide()
        local model = anim.Spell
        if model and model.ClearModel then
            model:ClearModel()
            model:Hide()
            model.Show = noop
        end
    end
end

---------------------------------------------------------------------------
-- 15. Pet ring: ConsolePort's pet portrait (and the ring of pet buttons around it) in
--     the bottom left corner of the screen instead of above the right end of the bar, a
--     bit smaller, with the lowest and leftmost pet buttons touching the screen edges.
--     /thor petring left|right (right: bottom right corner), /thor petringscale 50-150.
---------------------------------------------------------------------------
-- From ConsolePortBar's Pet.lua: the portrait is 64 x 64; eight 40 px buttons sit on a
-- 60 px circle around its centre, moved 8 down. So the lowest button's bottom edge is
-- 60 + 8 + 20 = 88 below the portrait's centre, and the outermost buttons' sides are
-- 60 + 20 = 80 to its left and right (in the pet's own, unscaled units).
local PET_HALF, PET_RING_SIDE, PET_RING_LOW = 32, 80, 88

local function applyPetRing()
    local pet = _G.ConsolePortBarPet
    if not pet or not db then return end
    if InCombatLockdown() then pending.petRing = true; return end
    pending.petRing = nil
    pet:SetScale((db.petRingScale or 80) / 100)
    pet:ClearAllPoints()
    -- Offsets are in the pet's own (scaled) units, so the ring fits the corner at any size.
    local x, y = PET_RING_SIDE - PET_HALF, PET_RING_LOW - PET_HALF
    if db.petRingLeft then
        pet:SetPoint("BOTTOMLEFT", UIParent, "BOTTOMLEFT", x, y)
    else
        pet:SetPoint("BOTTOMRIGHT", UIParent, "BOTTOMRIGHT", -x, y)
    end
end

---------------------------------------------------------------------------
-- 16. Bag sort: a sort button next to the search button in Bagnon's bag window (and bank
--     window while the bank is open), when the app's Settings > Interface > Sort button
--     in Bagnon is on (off by default; ThorControlsApp.bagSort, /thor sortbutton on|off).
--     WoW 3.3.5 has no sort of its own, so items are swapped one at a time, each after
--     the last swap's slots unlock. Hearthstone first, then materials, food & drink, other
--     consumables and the other item types (see itemRank), each by subtype, quality, item
--     level and name; grey junk last; empty slots at the end. Profession bags, quivers and
--     soul bags are left alone. Stops in combat. /thor sort, /thor sort bank.
---------------------------------------------------------------------------
-- Materials (Trade Goods) first, then food & drink, then the other consumables, then the
-- rest. Item types come localized from the auction house lists; 3.3.5 order: Weapon,
-- Armor, Container, Consumable, Glyph, Trade Goods, Projectile, Quiver, Recipe, Gem,
-- Miscellaneous, Quest. Consumable subtype 1 is Food & Drink.
local classRanks, consumableClass, foodSubclass
local function itemRank(item)
    if not classRanks then
        classRanks = {}
        local classes = { GetAuctionItemClasses() }
        consumableClass = classes[4]
        foodSubclass = (GetAuctionItemSubClasses(4))
        local rank = 0
        for _, i in ipairs({ 6, 4, 10, 5, 9, 1, 2, 3, 7, 8, 12, 11 }) do
            rank = rank + 1
            if i == 4 then rank = rank + 1 end -- food & drink takes the rank before
            if classes[i] then classRanks[classes[i]] = rank end
        end
    end
    local rank = classRanks[item.class] or 99
    if item.class == consumableClass and item.sub == foodSubclass then rank = rank - 1 end
    return rank
end

local function slotItem(bag, slot)
    local link = GetContainerItemLink(bag, slot)
    if not link then return false end
    local name, _, quality, ilvl, _, class, sub = GetItemInfo(link)
    return { link = link, id = tonumber(link:match("item:(%-?%d+)")) or 0, name = name or link,
        quality = quality or 1, ilvl = ilvl or 0, class = class or "", sub = sub or "" }
end

local HEARTHSTONE = 6948
local function sortsBefore(a, b)
    local ah, bh = a.id == HEARTHSTONE, b.id == HEARTHSTONE
    if ah ~= bh then return ah end
    local aj, bj = a.quality == 0, b.quality == 0
    if aj ~= bj then return bj end
    local ac, bc = itemRank(a), itemRank(b)
    if ac ~= bc then return ac < bc end
    if a.sub ~= b.sub then return a.sub < b.sub end
    if a.quality ~= b.quality then return a.quality > b.quality end
    if a.ilvl ~= b.ilvl then return a.ilvl > b.ilvl end
    if a.name ~= b.name then return a.name < b.name end
    if a.id ~= b.id then return a.id < b.id end
    return a.link < b.link
end

local bankOpen
local function sortBags(which)
    if which == "bank" then
        local bags = { -1 }
        for i = 1, GetNumBankSlots() do bags[#bags + 1] = NUM_BAG_SLOTS + i end
        return bags
    end
    return { 0, 1, 2, 3, 4 }
end

-- The swaps that put the bags in order. Items with the same link count as equal, so two
-- stacks of one item are never swapped (that would merge them instead).
local function planSort(bags)
    local slots, cur = {}, {}
    for _, bag in ipairs(bags) do
        local _, bagType = GetContainerNumFreeSlots(bag)
        if (bagType or 0) == 0 then
            for slot = 1, GetContainerNumSlots(bag) do
                slots[#slots + 1] = { bag = bag, slot = slot }
                cur[#slots] = slotItem(bag, slot)
            end
        end
    end
    local sorted = {}
    for i = 1, #slots do if cur[i] then sorted[#sorted + 1] = cur[i] end end
    table.sort(sorted, sortsBefore)
    local moves = {}
    for p = 1, #sorted do
        local want = sorted[p]
        if not (cur[p] and cur[p].link == want.link) then
            for q = p + 1, #slots do
                if cur[q] and cur[q].link == want.link then
                    moves[#moves + 1] = { from = slots[q], to = slots[p], link = want.link }
                    cur[p], cur[q] = cur[q], cur[p]
                    break
                end
            end
        end
    end
    return moves
end

local sortJob
local sortRunner = CreateFrame("Frame")
sortRunner:Hide()

local function sortButtons(enabled)
    for _, name in ipairs({ "BagnonFrameinventory", "BagnonFramebank" }) do
        local b = _G[name] and _G[name].thorSort
        if b then
            b.icon:SetDesaturated(not enabled)
            if enabled then b:Enable() else b:Disable() end
        end
    end
end

local function stopSort(reason)
    if not sortJob then return end
    sortJob = nil
    sortRunner:Hide()
    if CursorHasItem() then ClearCursor() end
    sortButtons(true)
    if reason then msg(reason) end
end

local function slotLocked(s)
    local _, _, locked = GetContainerItemInfo(s.bag, s.slot)
    return locked
end

sortRunner:SetScript("OnUpdate", function(self, elapsed)
    local job = sortJob
    if not job then self:Hide(); return end
    if InCombatLockdown() then stopSort("Sorting stopped: you entered combat."); return end
    if job.which == "bank" and not bankOpen then stopSort("Sorting stopped: the bank closed."); return end
    local last = job.last
    if last and (slotLocked(last.from) or slotLocked(last.to)) then
        job.waited = job.waited + elapsed
        if job.waited > 5 then stopSort("Sorting stopped: the game didn't answer.") end
        return
    end
    if CursorHasItem() then
        job.waited = job.waited + elapsed
        if job.waited > 1 then stopSort("Sorting stopped: something is on the cursor.") end
        return
    end
    local m = job.moves[job.i]
    if not m or GetContainerItemLink(m.from.bag, m.from.slot) ~= m.link then
        -- Done, or the bags changed underneath (loot, a merged stack): plan again.
        job.passes = job.passes + 1
        job.moves, job.i = planSort(job.bags), 1
        if #job.moves == 0 then stopSort(); return end
        if job.passes > 6 then stopSort("Sorting stopped: the bags kept changing."); return end
        return
    end
    PickupContainerItem(m.from.bag, m.from.slot)
    PickupContainerItem(m.to.bag, m.to.slot)
    if CursorHasItem() then ClearCursor() end
    job.last, job.i, job.waited = m, job.i + 1, 0
end)

local function startSort(which)
    if sortJob then return end
    if InCombatLockdown() then msg("Can't sort bags in combat."); return end
    if which == "bank" and not bankOpen then msg("Open the bank to sort it."); return end
    if CursorHasItem() then ClearCursor() end
    local bags = sortBags(which)
    sortJob = { which = which, bags = bags, moves = planSort(bags), i = 1, passes = 0, waited = 0 }
    if #sortJob.moves == 0 then sortJob = nil; return end
    sortButtons(false)
    sortRunner:Show()
end

-- A button like Bagnon's search toggle (same size and frame), with a bag icon.
local function makeSortButton(frame, which)
    local b = CreateFrame("Button", frame:GetName() .. "ThorSort", frame)
    b:SetWidth(20)
    b:SetHeight(20)
    b:RegisterForClicks("AnyUp")
    local nt = b:CreateTexture()
    nt:SetTexture([[Interface\Buttons\UI-Quickslot2]])
    nt:SetWidth(64 * 20 / 36)
    nt:SetHeight(64 * 20 / 36)
    nt:SetPoint("CENTER", 0, -1)
    b:SetNormalTexture(nt)
    local pt = b:CreateTexture()
    pt:SetTexture([[Interface\Buttons\UI-Quickslot-Depress]])
    pt:SetAllPoints(b)
    b:SetPushedTexture(pt)
    local ht = b:CreateTexture()
    ht:SetTexture([[Interface\Buttons\ButtonHilight-Square]])
    ht:SetAllPoints(b)
    b:SetHighlightTexture(ht)
    b.icon = b:CreateTexture()
    b.icon:SetAllPoints(b)
    b.icon:SetTexture([[Interface\Icons\INV_Misc_Bag_10]])
    b:SetScript("OnClick", function() startSort(which) end)
    b:SetScript("OnEnter", function(self)
        GameTooltip:SetOwner(self, "ANCHOR_LEFT")
        GameTooltip:SetText(which == "bank" and "Sort bank" or "Sort bags")
        GameTooltip:Show()
    end)
    b:SetScript("OnLeave", function() GameTooltip:Hide() end)
    return b
end

-- Bagnon lays out its menu buttons (player, bags, search) left to right and puts the title
-- after the last one. After it has, add the sort button to that row, right of search.
-- Windows already laid out (before the hook, or before the setting changed): again.
local function relayoutBagnon()
    for _, id in ipairs({ "inventory", "bank" }) do
        local f = _G["BagnonFrame" .. id]
        if f and f.Layout then f:Layout() end
    end
end

local bagnonHooked
local function hookBagnon()
    if bagnonHooked or not LibStub then return end
    local ace = LibStub("AceAddon-3.0", true)
    local bagnon = ace and ace:GetAddon("Bagnon", true)
    if not bagnon or not bagnon.Frame then return end
    bagnonHooked = true
    hooksecurefunc(bagnon.Frame, "PlaceMenuButtons", function(self)
        local id = self.GetFrameID and self:GetFrameID()
        local which = (id == "inventory" and "bags") or (id == "bank" and "bank")
        local list = self.menuButtons
        if not which or not list then return end
        if not db.bagSort then
            if self.thorSort then self.thorSort:Hide() end
            return
        end
        self.thorSort = self.thorSort or makeSortButton(self, which)
        local b = self.thorSort
        b:ClearAllPoints()
        if #list > 0 then
            b:SetPoint("TOPLEFT", list[#list], "TOPRIGHT", 4, 0)
        else
            b:SetPoint("TOPLEFT", self, "TOPLEFT", 8, -8)
        end
        b:Show()
        table.insert(list, b)
    end)
    relayoutBagnon()
end

---------------------------------------------------------------------------
-- 17. World map player arrow: bigger. 3.3.5 draws it as a small 3D model on the world map
--     (PlayerArrowFrame, with PlayerArrowEffectFrame's glow behind it); scaled each time the
--     map opens. /thor maparrow 50-300 (percent, default 150).
---------------------------------------------------------------------------
local mapArrowHooked
local function applyMapArrow()
    if not db then return end
    local scale = (db.mapArrowScale or 150) / 100
    for _, name in ipairs({ "PlayerArrowFrame", "PlayerArrowEffectFrame" }) do
        local model = _G[name]
        if model and model.SetModelScale then model:SetModelScale(scale) end
    end
    if not mapArrowHooked and WorldMapFrame then
        mapArrowHooked = true
        WorldMapFrame:HookScript("OnShow", applyMapArrow)
    end
end

---------------------------------------------------------------------------
-- Events
---------------------------------------------------------------------------
events:RegisterEvent("ADDON_LOADED")
events:RegisterEvent("BANKFRAME_OPENED")
events:RegisterEvent("BANKFRAME_CLOSED")
events:RegisterEvent("PLAYER_LOGIN")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:RegisterEvent("PLAYER_REGEN_ENABLED")
events:RegisterEvent("PLAYER_REGEN_DISABLED")
events:RegisterEvent("UNIT_PET")
events:SetScript("OnEvent", function(_, event, arg1)
    if event == "ADDON_LOADED" then
        if arg1 == "ThorControls" then
            ThorControlsDB = ThorControlsDB or {}
            db = ThorControlsDB
            for k, v in pairs(defaults) do if db[k] == nil then db[k] = v end end
            -- 1.39: the global cooldown sweep is shown again (cheap since section 13);
            -- switch it back on once for those who had the old default.
            if not db.gcdShown then db.gcdShown = true; db.hideGcd = false end
            -- ConsolePort found no settings (an account's first login, when the app didn't
            -- know the account name): it made defaults with an empty calibration.
            if type(ConsolePortSettings) == "table" and not next(ConsolePortSettings.calibration or {}) then
                for k, v in pairs(FRESH_SETTINGS) do ConsolePortSettings[k] = v end
                freshSetup = true
            end
            -- XP bar settings from the app (AppSettings.lua, rewritten by the app each launch).
            local app = ThorControlsApp
            if type(app) == "table" then
                for _, k in ipairs({ "xpShow", "xpText", "xpWidth", "xpHeight", "xpX", "xpY", "bagSort" }) do
                    if app[k] ~= nil then db[k] = app[k] end
                end
            end
            applyCalibration()
            applyDefaultBindings()
            db.runWalkMoved = db.runWalkMoved or {}
            local who = (UnitName("player") or "?") .. "-" .. (GetRealmName() or "?")
            if not db.runWalkMoved[who] then
                migrateRunWalk()
                db.runWalkMoved[who] = true
            end
            -- ConsolePortBar loaded first (OptionalDeps), so its own ADDON_LOADED has passed;
            -- its settings are read when the bar is set up at login, so set them now.
            applyTriggers()
        elseif arg1 == "ConsolePortBar" then
            applyTriggers()
            hookWatchBars()
        end
    elseif event == "PLAYER_LOGIN" then
        followBack()
        applyNameplates()
        if freshSetup then
            freshSetup = nil
            if ConsolePortSplashFrame then ConsolePortSplashFrame:Hide() end
            StaticPopup_Show("THORCONTROLS_RELOAD")
        end
        if triggersRemoved and not InCombatLockdown() then
            triggersRemoved = nil
            hideTriggerButtons()
        end
        applyCalibration()
        reloadConsolePortBindings()
        applyCamera()
        if IsAddOnLoaded("ConsolePortBar") then hookWatchBars() end
        hideBlizzardBars()
        applyPetBar()
        applyTrail()
        hideUtilityModel()
        bindBattlegroundsKey()
        if db.spikes then setSpikes(true) end
        fixBarCooldowns()
        applyPetRing()
        applyMapArrow()
        if IsAddOnLoaded("Bagnon") then hookBagnon() end
    elseif event == "PLAYER_ENTERING_WORLD" then
        applyPetBar()
        applyPetRing()
        applyTrail()
        fixBarCooldowns()
    elseif event == "PLAYER_REGEN_DISABLED" then
        fixBarCooldowns() -- buttons added by layout changes since login
        stopSort(sortJob and "Sorting stopped: you entered combat.")
    elseif event == "BANKFRAME_OPENED" then
        bankOpen = true
    elseif event == "BANKFRAME_CLOSED" then
        bankOpen = nil
    elseif event == "UNIT_PET" then
        if arg1 == "player" then applyPetBar() end
    elseif event == "PLAYER_REGEN_ENABLED" then
        if pending.cpBindings then reloadConsolePortBindings() end
        if pending.nameplates then applyNameplates() end
        if pending.camera then applyCamera() end
        if pending.pet then applyPetBar() end
        if pending.petRing then applyPetRing() end
        if pending.blizz then hideBlizzardBars() end
    end
end)

---------------------------------------------------------------------------
-- 10. Spike finder (/thor spikes on): reports each frame that took over
-- SPIKE_MS, with the events handled during it and, when /thor profile is on,
-- which addons ran how long in it. Diagnosis only; off by default, not saved.
---------------------------------------------------------------------------
-- Grouped by family: digits in names become #, so the 12 buttons of one bar add up;
-- an unnamed frame counts under its nearest named parent.
local function family(fr)
    local name, p, depth = fr:GetName(), fr, 0
    while not name and p and depth < 6 do
        p = p:GetParent()
        name = p and p:GetName()
        depth = depth + 1
    end
    name = name and name:gsub("%d+", "#") or "(unnamed)"
    return fr:GetName() and name or (name .. " > " .. fr:GetObjectType())
end

local SPIKE_MS = 150
local spikes = CreateFrame("Frame")
local spikeEvents, spikeCpu, spikeScript = {}, {}, 0
local lastReport = 0

local function snapshotCpu()
    UpdateAddOnCPUUsage()
    for i = 1, GetNumAddOns() do
        if IsAddOnLoaded(i) then spikeCpu[i] = GetAddOnCPUUsage(i) end
    end
    spikeScript = GetScriptCPUUsage()
end

-- When each event arrived in the frame (ms after the previous frame's update), and
-- the text of executed chat/macro lines, to see where in a slow frame the time goes.
local spikeTimeline, frameStart, lastMem = {}, 0, 0
spikes:SetScript("OnEvent", function(_, event, arg1)
    spikeEvents[event] = (spikeEvents[event] or 0) + 1
    if #spikeTimeline < 60 then
        local label = event
        if event == "EXECUTE_CHAT_LINE" and arg1 then label = event .. "(" .. tostring(arg1):sub(1, 40) .. ")" end
        spikeTimeline[#spikeTimeline + 1] = string.format("+%d %s", debugprofilestop() - frameStart, label)
    end
end)
spikes:SetScript("OnUpdate", function(_, elapsed)
    local profiling = GetCVar("scriptProfile") == "1"
    if elapsed * 1000 >= SPIKE_MS and GetTime() - lastReport > 1 then
        lastReport = GetTime()
        local ev = {}
        for e, n in pairs(spikeEvents) do ev[#ev + 1] = n > 1 and (e .. "x" .. n) or e end
        table.sort(ev)
        local line = string.format("Slow frame %d ms. Events: %s", elapsed * 1000, #ev > 0 and table.concat(ev, ", ") or "none")
        report(line)
        local mem = collectgarbage("count")
        report(string.format("  Lua memory: %.1f MB before, %.1f MB after (%+.1f MB)", lastMem / 1024, mem / 1024, (mem - lastMem) / 1024))
        spikeTimeline[#spikeTimeline + 1] = string.format("+%d next frame", debugprofilestop() - frameStart)
        report("  Timeline: " .. table.concat(spikeTimeline, ", "))
        if profiling then
            local before, beforeScript = {}, spikeScript
            for i, v in pairs(spikeCpu) do before[i] = v end
            snapshotCpu()
            local list = {}
            for i, v in pairs(spikeCpu) do
                local d = v - (before[i] or v)
                if d >= 1 then list[#list + 1] = { name = GetAddOnInfo(i), ms = d } end
            end
            table.sort(list, function(a, b) return a.ms > b.ms end)
            local parts = {}
            for k = 1, math.min(6, #list) do parts[k] = string.format("%s %d", list[k].name, list[k].ms) end
            report(string.format("  Addon code in it: %d ms of %d. %s", spikeScript - beforeScript, elapsed * 1000, table.concat(parts, ", ")))
        end
    elseif profiling then
        snapshotCpu()
    end
    wipe(spikeEvents)
    wipe(spikeTimeline)
    lastMem = collectgarbage("count")
    frameStart = debugprofilestop()
end)
spikes:Hide()

-- Stays on across reloads until /thor spikes off.
setSpikes = function(on)
    if on then
        wipe(spikeEvents)
        if GetCVar("scriptProfile") == "1" then snapshotCpu() end
        spikes:RegisterAllEvents()
        spikes:Show()
        report("Reporting frames slower than " .. SPIKE_MS .. " ms. /thor spikes off to stop.")
    else
        spikes:UnregisterAllEvents()
        spikes:Hide()
        report("Spike reports off.")
    end
end

---------------------------------------------------------------------------
-- /thor
---------------------------------------------------------------------------
SLASH_THORCONTROLS1 = "/thor"
SlashCmdList.THORCONTROLS = function(input)
    local cmd, value = strsplit(" ", strlower(strtrim(input or "")), 2)
    if cmd == "xpbar" and value then
        -- Typed by the app when its XP bar settings change: show(0/1) text(0/1) width height x y.
        local v, t, w, h, x, y = strsplit(" ", value)
        v, t, w, h, x, y = tonumber(v), tonumber(t), tonumber(w), tonumber(h), tonumber(x), tonumber(y)
        if v and t and w and h and x and y then
            db.xpShow, db.xpText, db.xpWidth, db.xpHeight, db.xpX, db.xpY = v == 1, t == 1, w, h, x, y
            relayoutWatchBars()
            local bars = ConsolePortBarWatchBars and ConsolePortBarWatchBars.bars or {}
            for _, bar in ipairs(bars) do
                local text = bar.OverlayFrame and bar.OverlayFrame.Text
                if text then if db.xpText then text:Show() else text:Hide() end end
            end
        end
    elseif cmd == "xp" and tonumber(value) then
        db.xpY = tonumber(value)
        relayoutWatchBars()
        msg("XP bar moved up " .. db.xpY .. ".")
    elseif cmd == "xpwidth" and tonumber(value) then
        db.xpWidth = tonumber(value)
        relayoutWatchBars()
        msg("XP bar width " .. db.xpWidth .. "% (100 = D-pad Left to A).")
    elseif cmd == "xptext" and (value == "on" or value == "off") then
        db.xpText = value == "on"
        relayoutWatchBars()
        local bars = ConsolePortBarWatchBars and ConsolePortBarWatchBars.bars or {}
        for _, bar in ipairs(bars) do
            local text = bar.OverlayFrame and bar.OverlayFrame.Text
            if text then if db.xpText then text:Show() else text:Hide() end end
        end
        msg("XP bar text " .. onoff(db.xpText))
    elseif cmd == "petbar" and (value == "on" or value == "off") then
        db.petbar = value == "on"
        if db.petbar then applyPetBar() else msg("Default pet bar will be back after /reload.") end
        msg("Hide default pet bar " .. onoff(db.petbar))
    elseif cmd == "triggers" and (value == "on" or value == "off") then
        if InCombatLockdown() then msg("Leave combat first."); return end
        db.hideTriggers = value == "off"
        if applyTriggers() then ReloadUI() else msg("ConsolePort bar isn't loaded; the change applies next time it loads.") end
    elseif cmd == "gcd" and (value == "on" or value == "off") then
        db.hideGcd = value == "off"
        msg("Global cooldown sweep on action buttons " .. onoff(not db.hideGcd))
    elseif cmd == "profile" and (value == "on" or value == "off") then
        -- WoW's own per-addon CPU measuring; costs a little itself, so only while looking.
        SetCVar("scriptProfile", value == "on" and "1" or "0")
        ReloadUI()
    elseif cmd == "cpu" then
        if GetCVar("scriptProfile") ~= "1" then
            report("Turn measuring on first: /thor profile on (reloads), play a bit, then /thor cpu.")
            return
        end
        UpdateAddOnCPUUsage()
        local list, total = {}, 0
        for i = 1, GetNumAddOns() do
            if IsAddOnLoaded(i) then
                local ms = GetAddOnCPUUsage(i)
                total = total + ms
                list[#list + 1] = { name = GetAddOnInfo(i), ms = ms }
            end
        end
        table.sort(list, function(a, b) return a.ms > b.ms end)
        report(string.format("Addon CPU since the last reload (%.1f s total):", total / 1000))
        for i = 1, math.min(10, #list) do
            report(string.format("%2d. %s  %.1f s (%d%%)", i, list[i].name, list[i].ms / 1000,
                total > 0 and math.floor(list[i].ms / total * 100 + 0.5) or 0))
        end
        report("/thor profile off when done.")
    elseif cmd == "frames" then
        -- Which frames' scripts (OnEvent, OnUpdate, ...) used the most Lua time since the
        -- reload, Blizzard's and secure ones included (the per-addon numbers miss those).
        if GetCVar("scriptProfile") ~= "1" then
            report("Turn measuring on first: /thor profile on (reloads), play a bit, then /thor frames.")
            return
        end
        local groups, total, frames = {}, 0, 0
        local f = EnumerateFrames()
        while f do
            local ms = GetFrameCPUUsage(f, false)
            if ms and ms > 0 then
                total, frames = total + ms, frames + 1
                local key = family(f)
                local g = groups[key]
                if not g then g = { name = key, ms = 0, n = 0 }; groups[key] = g end
                g.ms, g.n = g.ms + ms, g.n + 1
            end
            f = EnumerateFrames(f)
        end
        local list = {}
        for _, g in pairs(groups) do list[#list + 1] = g end
        table.sort(list, function(a, b) return a.ms > b.ms end)
        report(string.format("Frame script time since reload: %.1f s in %d frames (all Lua %.1f s):",
            total / 1000, frames, GetScriptCPUUsage() / 1000))
        for i = 1, math.min(20, #list) do
            report(string.format("%2d. %s x%d  %.2f s", i, list[i].name, list[i].n, list[i].ms / 1000))
        end
    elseif cmd == "spikes" and (value == "on" or value == "off") then
        db.spikes = value == "on"
        setSpikes(db.spikes)
    elseif cmd == "bar" and (value == "on" or value == "off") then
        if value == "on" then EnableAddOn("ConsolePortBar") else DisableAddOn("ConsolePortBar") end
        ReloadUI()
    elseif cmd == "log" and value == "clear" then
        if db then db.log = nil end
        msg("Measurement log cleared.")
    elseif cmd == "castbar" and (value == "on" or value == "off") then
        if InCombatLockdown() then msg("Leave combat first."); return end
        db.defaultCastBar = value == "on"
        if applyTriggers() then ReloadUI() else msg("ConsolePort bar isn't loaded; the change applies next time it loads.") end
    elseif cmd == "trail" and (value == "on" or value == "off") then
        db.hideTrail = value == "off"
        applyTrail()
        msg("Controller icon next to the pointer " .. onoff(not db.hideTrail))
    elseif cmd == "defaults" then
        if InCombatLockdown() then msg("Leave combat first."); return end
        applyDefaultBindings(true)
        ReloadUI()
    elseif cmd == "sortbutton" and (value == "on" or value == "off") then
        -- Typed by the app when Settings > Interface > Sort button in Bagnon changes.
        db.bagSort = value == "on"
        if IsAddOnLoaded("Bagnon") then
            if bagnonHooked then relayoutBagnon() else hookBagnon() end
        end
    elseif cmd == "maparrow" and tonumber(value) and tonumber(value) >= 50 and tonumber(value) <= 300 then
        db.mapArrowScale = tonumber(value)
        applyMapArrow()
        msg("World map arrow size " .. db.mapArrowScale .. "%.")
    elseif cmd == "sort" then
        startSort(value == "bank" and "bank" or "bags")
    elseif cmd == "petring" and (value == "left" or value == "right") then
        db.petRingLeft = value == "left"
        applyPetRing()
        msg("Pet ring on the " .. value .. ".")
    elseif cmd == "petringscale" and tonumber(value) and tonumber(value) >= 50 and tonumber(value) <= 150 then
        db.petRingScale = tonumber(value)
        applyPetRing()
        msg("Pet ring size " .. db.petRingScale .. "%.")
    elseif cmd == "calibrate" then
        if applyCalibration() then msg("Controller calibration restored.") end
        reloadConsolePortBindings()
    else
        msg("Hide default pet bar " .. onoff(db.petbar) .. " | L2/R2 icons " .. onoff(not db.hideTriggers) ..
            " | XP bar width " .. (db.xpWidth or 100) .. "%, height " .. (db.xpHeight or 11) ..
            ", offset " .. (db.xpX or 0) .. "," .. (db.xpY or 0) ..
            " | XP bar text " .. onoff(db.xpText))
        msg("XP bar: set its text, size and position in the app (Settings > XP bar).")
        msg("/thor petbar on|off  - hide Blizzard's default pet bar")
        msg("/thor triggers on|off  - show the L2/R2 icons on the ConsolePort bar (reloads)")
        msg("/thor castbar on|off  - WoW's own cast bar instead of ConsolePort's (reloads)")
        msg("/thor gcd on|off  - global cooldown sweep on the action buttons")
        msg("/thor profile on|off, /thor cpu  - measure which addons use the most CPU")
        msg("/thor spikes on|off  - report slow frames and what ran in them")
        msg("/thor bar on|off  - turn ConsolePort's action bar on/off (reloads)")
        msg("/thor trail on|off  - controller icon next to the pointer")
        msg("/thor sort, /thor sort bank  - sort your bags (or the bank, while it's open)")
        msg("/thor petring left|right, /thor petringscale 50-150  - where and how big ConsolePort's pet ring is")
        msg("/thor maparrow 50-300  - size of your arrow on the world map (percent)")
        msg("/thor calibrate  - put the controller calibration back")
        msg("/thor defaults  - replace this character's controller bindings with the defaults (reloads)")
    end
end
