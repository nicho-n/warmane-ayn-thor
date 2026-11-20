-- BarKeeper: keeps this computer's action bars.
--
-- WoW 3.3.5 keeps action bars on the server, so moving things around on one computer (a PC)
-- also moves them on another (the Thor). BarKeeper keeps a copy of this computer's bars, per
-- character and per talent spec, in its saved variables (which stay on this computer), and at
-- login and after a spec switch puts back every slot that differs. Changes made here are
-- added to the copy as they happen.
--
-- BarKeeperDB = { off = true or nil, specs = { [talent group] = { [slot] = entry or false } } }
-- entry: { kind, ... } as made by entryOf; false = empty slot.

local SLOTS = 120
local SETTLE = 2 -- seconds after login / a spec switch for the server's bars to arrive

local db
local settled = false   -- the copy is in step with the bars: changes are recorded
local failed = {}       -- slot -> key of what's there, for slots that couldn't be put back
local pending = nil     -- restore waiting for the end of combat / an empty cursor

local function say(msg) DEFAULT_CHAT_FRAME:AddMessage("|cff66ccffBarKeeper:|r " .. msg) end

local function spec() return GetActiveTalentGroup and GetActiveTalentGroup() or 1 end

-- What's in a slot, in a form that can be put back later (and on a later login).
local function entryOf(slot)
    local kind, id, sub, globalId = GetActionInfo(slot)
    if not kind then return false end
    if kind == "spell" then
        local name, rank = GetSpellInfo(globalId or id)
        if name then return { "spell", name, rank } end
    elseif kind == "item" then
        return { "item", id }
    elseif kind == "macro" then
        local name = GetMacroInfo(id)
        if name then return { "macro", name } end
    elseif kind == "companion" then
        -- id is the companion's index or its spell ID; the icon tells which one it is.
        local tex = GetActionTexture(slot)
        for i = 1, GetNumCompanions(sub) do
            local _, _, spellId, icon = GetCompanionInfo(sub, i)
            if (i == id or spellId == id) and icon == tex then return { "companion", sub, spellId } end
        end
    elseif kind == "equipmentset" then
        return { "equipmentset", id }
    end
    return nil -- something BarKeeper can't put back: left alone
end

local function keyOf(e)
    if e == false then return "empty" end
    if not e then return nil end
    if e[1] == "spell" then return "spell:" .. e[2] end -- any rank (learning a rank upgrades bars)
    return table.concat({ e[1], tostring(e[2]), tostring(e[3] or "") }, ":")
end

-- Puts entry e on the cursor; true if it got there.
local function pickup(e)
    local kind = e[1]
    if kind == "spell" then
        local best, exact
        local i = 1
        while true do
            local name, rank = GetSpellName(i, BOOKTYPE_SPELL)
            if not name then break end
            if name == e[2] then
                best = i
                if rank == e[3] then exact = i end
            end
            i = i + 1
        end
        if not (exact or best) then return false end
        PickupSpell(exact or best, BOOKTYPE_SPELL)
    elseif kind == "item" then
        PickupItem(e[2])
    elseif kind == "macro" then
        local index = GetMacroIndexByName(e[2])
        if not index or index == 0 then return false end
        PickupMacro(index)
    elseif kind == "companion" then
        for i = 1, GetNumCompanions(e[2]) do
            if select(3, GetCompanionInfo(e[2], i)) == e[3] then PickupCompanion(e[2], i); break end
        end
    elseif kind == "equipmentset" then
        if not PickupEquipmentSetByName then return false end
        PickupEquipmentSetByName(e[2])
    end
    return GetCursorInfo() ~= nil
end

local function record(slot)
    local saved = db.specs[spec()]
    if not saved then return end
    local e = entryOf(slot)
    if e == nil then return end
    -- A slot that couldn't be put back keeps the copy's entry until it's changed here.
    if failed[slot] then
        if keyOf(e) == failed[slot] then return end
        failed[slot] = nil
    end
    saved[slot] = e
end

local function recordAll()
    for slot = 1, SLOTS do record(slot) end
end

local function restore()
    pending = nil
    if InCombatLockdown() or GetCursorInfo() then pending = true; return end
    local g = spec()
    local saved = db.specs[g]
    wipe(failed)
    if not saved then
        -- First time on this computer: the bars as they are now become the copy.
        db.specs[g] = {}
        recordAll()
        settled = true
        return
    end
    if db.off then settled = true; recordAll(); return end
    local put, missed = 0, 0
    for slot = 1, SLOTS do
        local want = saved[slot]
        if want ~= nil and keyOf(entryOf(slot)) ~= keyOf(want) then
            if want == false then
                PickupAction(slot)
                ClearCursor()
                put = put + 1
            elseif pickup(want) then
                PlaceAction(slot)
                ClearCursor()
                put = put + 1
            else
                ClearCursor()
                failed[slot] = keyOf(entryOf(slot)) or "?"
                missed = missed + 1
            end
        end
    end
    if put > 0 or missed > 0 then
        local msg = "put back " .. put .. " action" .. (put == 1 and "" or "s") .. " changed elsewhere"
        if missed > 0 then msg = msg .. " (" .. missed .. " couldn't be: no longer known)" end
        say(msg .. ".")
    end
    -- Bar changes from the restore arrive as events; record once they're through.
    local wait = 1
    local f = CreateFrame("Frame")
    f:SetScript("OnUpdate", function(self, elapsed)
        wait = wait - elapsed
        if wait > 0 then return end
        self:SetScript("OnUpdate", nil)
        if spec() ~= g then return end
        settled = true
        recordAll()
    end)
end

local timer = CreateFrame("Frame")
timer:Hide()
timer:SetScript("OnUpdate", function(self, elapsed)
    self.left = self.left - elapsed
    if self.left <= 0 then self:Hide(); restore() end
end)
local function restoreSoon(delay)
    settled = false
    timer.left = delay
    timer:Show()
end

local events = CreateFrame("Frame")
events:RegisterEvent("ADDON_LOADED")
events:RegisterEvent("PLAYER_ENTERING_WORLD")
events:RegisterEvent("ACTIONBAR_SLOT_CHANGED")
events:RegisterEvent("ACTIVE_TALENT_GROUP_CHANGED")
events:RegisterEvent("PLAYER_REGEN_ENABLED")
events:RegisterEvent("CURSOR_UPDATE")
events:RegisterEvent("PLAYER_LOGOUT")
events:SetScript("OnEvent", function(self, event, arg1)
    if event == "ADDON_LOADED" then
        if arg1 ~= "BarKeeper" then return end
        BarKeeperDB = BarKeeperDB or {}
        db = BarKeeperDB
        db.specs = db.specs or {}
    elseif not db then
        return
    elseif event == "PLAYER_ENTERING_WORLD" then
        restoreSoon(SETTLE)
    elseif event == "ACTIVE_TALENT_GROUP_CHANGED" then
        restoreSoon(SETTLE)
    elseif event == "ACTIONBAR_SLOT_CHANGED" then
        if not settled then return end
        if arg1 and arg1 >= 1 and arg1 <= SLOTS then record(arg1) else recordAll() end
    elseif event == "PLAYER_REGEN_ENABLED" or event == "CURSOR_UPDATE" then
        if pending and not timer:IsShown() then restoreSoon(0.5) end
    elseif event == "PLAYER_LOGOUT" then
        if settled then recordAll() end
    end
end)

SLASH_BARKEEPER1 = "/barkeeper"
SlashCmdList.BARKEEPER = function(msg)
    msg = (msg or ""):lower():match("^%s*(.-)%s*$")
    if not db then return end
    if msg == "off" then
        db.off = true
        say("off: the bars are left as the server has them at login (still copied, for /barkeeper on).")
    elseif msg == "on" then
        db.off = nil
        say("on: this computer's bars are put back at login.")
    elseif msg == "save" then
        db.specs[spec()] = {}
        wipe(failed)
        recordAll()
        settled = true
        say("the bars as they are now are this computer's copy (this spec).")
    elseif msg == "restore" then
        restoreSoon(0)
    else
        say((db.off and "off" or "on") .. ". WoW keeps action bars on the server, so changes on another computer show up here too; BarKeeper puts this computer's back at login and after a spec switch.")
        say("/barkeeper off | on | save (the bars now become the copy) | restore (put the copy back now)")
    end
end
