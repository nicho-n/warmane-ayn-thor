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

local defaults = { petbar = true, xpLift = 10, hideTriggers = true, hideTrail = true, defaultCastBar = true, hideGcd = false }
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

local function applyTriggers()
    local cfg = ConsolePortBarSetup
    if not db or not cfg or not cfg.layout then return false end
    -- WoW's own cast bar instead of ConsolePortBar's restyled one in the middle of its bar.
    cfg.defaultCastBar = db.defaultCastBar and true or nil
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
-- Events
---------------------------------------------------------------------------
events:RegisterEvent("ADDON_LOADED")
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
        applyCalibration()
        reloadConsolePortBindings()
        applyCamera()
        if IsAddOnLoaded("ConsolePortBar") then hookWatchBars() end
        hideBlizzardBars()
        applyPetBar()
        applyTrail()
        hideUtilityModel()
        if db.spikes then setSpikes(true) end
        fixBarCooldowns()
    elseif event == "PLAYER_ENTERING_WORLD" then
        applyPetBar()
        applyTrail()
        fixBarCooldowns()
    elseif event == "PLAYER_REGEN_DISABLED" then
        fixBarCooldowns() -- buttons added by layout changes since login
    elseif event == "UNIT_PET" then
        if arg1 == "player" then applyPetBar() end
    elseif event == "PLAYER_REGEN_ENABLED" then
        if pending.cpBindings then reloadConsolePortBindings() end
        if pending.camera then applyCamera() end
        if pending.pet then applyPetBar() end
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
    elseif cmd == "calibrate" then
        if applyCalibration() then msg("Controller calibration restored.") end
        reloadConsolePortBindings()
    else
        msg("Hide default pet bar " .. onoff(db.petbar) .. " | L2/R2 icons " .. onoff(not db.hideTriggers) ..
            " | XP bar lift " .. (db.xpLift or 0))
        msg("/thor xp <number>  - move the XP bar up (e.g. 10, 20; 0 = original)")
        msg("/thor petbar on|off  - hide Blizzard's default pet bar")
        msg("/thor triggers on|off  - show the L2/R2 icons on the ConsolePort bar (reloads)")
        msg("/thor castbar on|off  - WoW's own cast bar instead of ConsolePort's (reloads)")
        msg("/thor gcd on|off  - global cooldown sweep on the action buttons")
        msg("/thor profile on|off, /thor cpu  - measure which addons use the most CPU")
        msg("/thor spikes on|off  - report slow frames and what ran in them")
        msg("/thor bar on|off  - turn ConsolePort's action bar on/off (reloads)")
        msg("/thor trail on|off  - controller icon next to the pointer")
        msg("/thor calibrate  - put the controller calibration back")
        msg("/thor defaults  - replace this character's controller bindings with the defaults (reloads)")
    end
end
