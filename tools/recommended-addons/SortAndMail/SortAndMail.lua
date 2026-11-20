-- SortAndMail: a sort button in Bagnon's bag and bank windows, and an Open All button in the
-- mailbox.
--
-- The sort is the Warmane Ayn Thor app's (ThorControls section 16). Where ThorControls is
-- loaded (the Thor), it has the sort button already, so only the mail button is added here.

local function msg(text)
    DEFAULT_CHAT_FRAME:AddMessage("|cff69ccf0SortAndMail:|r " .. text)
end

---------------------------------------------------------------------------
-- Bag sort. WoW 3.3.5 has no sort of its own, so items are swapped one at a time, each after
-- the last swap's slots unlock. Hearthstone first, then materials, food & drink, other
-- consumables and the other item types (see itemRank), each by subtype, quality, item level
-- and name; grey junk last; empty slots at the end. Profession bags, quivers and soul bags
-- are left alone. Stops in combat. /sortandmail sort, /sortandmail sort bank.
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
-- Open All: takes the gold, then the items, from every mail in the inbox, one at a time,
-- each after the server has answered the last. Cash on delivery and Game Master mails are
-- left alone, as are letters with nothing attached. Stops when the bags are full or the
-- mailbox closes.
---------------------------------------------------------------------------
local mailJob
local mailRunner = CreateFrame("Frame")
mailRunner:Hide()
local openAll

local function freeBagSlots()
    local free = 0
    for bag = 0, NUM_BAG_SLOTS do
        local n, bagType = GetContainerNumFreeSlots(bag)
        if (bagType or 0) == 0 then free = free + (n or 0) end
    end
    return free
end

local function stopMail(reason)
    if not mailJob then return end
    local job = mailJob
    mailJob = nil
    mailRunner:Hide()
    if openAll then openAll:Enable() end
    if reason then msg(reason); return end
    local parts = {}
    if job.money > 0 then
        parts[#parts + 1] = GetCoinTextureString and GetCoinTextureString(job.money) or (job.money .. " copper")
    end
    if job.items > 0 then parts[#parts + 1] = job.items .. " item" .. (job.items == 1 and "" or "s") end
    if #parts > 0 then msg("took " .. table.concat(parts, " and ") .. ".") end
    local shown, total = GetInboxNumItems()
    if total and shown and total > shown then
        msg((total - shown) .. " more mail waiting: close the mailbox and open it again for them.")
    end
end

-- The next thing to take, from the last mail up (taking everything from a mail can delete
-- it, which moves the ones after it).
local function nextTake()
    for i = GetInboxNumItems(), 1, -1 do
        local _, _, _, _, money, cod, _, hasItem, _, _, _, _, isGM = GetInboxHeaderInfo(i)
        if not isGM and (cod or 0) == 0 then
            if (money or 0) > 0 then return i, nil, money end
            if hasItem then
                for j = 1, ATTACHMENTS_MAX_RECEIVE do
                    if GetInboxItem(i, j) then return i, j end
                end
            end
        end
    end
end

local WAIT_MAX = 5
mailRunner:SetScript("OnUpdate", function(self, elapsed)
    local job = mailJob
    if not job then self:Hide(); return end
    job.wait = job.wait - elapsed
    if job.wait > 0 then return end
    if job.pending then
        -- Still in the mail: the server hasn't answered yet.
        local p = job.pending
        local still
        if p.item then
            still = GetInboxItem(p.mail, p.item) and GetInboxItemLink(p.mail, p.item) == p.link
        else
            still = select(5, GetInboxHeaderInfo(p.mail)) == p.money
        end
        if still and GetTime() - p.at < WAIT_MAX then job.wait = 0.1; return end
        if still then stopMail("Stopped: the server didn't answer."); return end
        if p.item then job.items = job.items + 1 else job.money = job.money + p.money end
        job.pending = nil
    end
    local mail, item, money = nextTake()
    if not mail then stopMail(); return end
    if item then
        if freeBagSlots() == 0 then stopMail("Stopped: your bags are full."); return end
        job.pending = { mail = mail, item = item, link = GetInboxItemLink(mail, item), at = GetTime() }
        TakeInboxItem(mail, item)
    else
        job.pending = { mail = mail, money = money, at = GetTime() }
        TakeInboxMoney(mail)
    end
    job.wait = 0.3
end)

local function startMail()
    if mailJob then return end
    if not nextTake() then msg("Nothing to take."); return end
    mailJob = { money = 0, items = 0, wait = 0 }
    if openAll then openAll:Disable() end
    mailRunner:Show()
end

-- Between the inbox's Prev and Next page buttons.
local function placeOpenAll()
    local prev, nxt = InboxPrevPageButton, InboxNextPageButton
    local l, y, r
    if prev then l, y = prev:GetCenter() end
    if nxt then r = nxt:GetCenter() end
    local fl, fb = InboxFrame:GetLeft(), InboxFrame:GetBottom()
    openAll:ClearAllPoints()
    if l and r and y and fl and fb then
        openAll:SetPoint("CENTER", InboxFrame, "BOTTOMLEFT", (l + r) / 2 - fl, y - fb)
    else
        openAll:SetPoint("BOTTOM", InboxFrame, "BOTTOM", -10, 95)
    end
end

local function makeOpenAll()
    if openAll or not InboxFrame then return end
    openAll = CreateFrame("Button", "SortAndMailOpenAll", InboxFrame, "UIPanelButtonTemplate")
    openAll:SetWidth(90)
    openAll:SetHeight(22)
    openAll:SetText("Open All")
    InboxFrame:HookScript("OnShow", placeOpenAll)
    placeOpenAll()
    openAll:SetScript("OnClick", startMail)
    openAll:SetScript("OnEnter", function(self)
        GameTooltip:SetOwner(self, "ANCHOR_RIGHT")
        GameTooltip:SetText("Open All")
        GameTooltip:AddLine("Takes the gold and items from every mail. Cash on delivery mail is left alone.", 1, 1, 1, true)
        GameTooltip:Show()
    end)
    openAll:SetScript("OnLeave", function() GameTooltip:Hide() end)
end

---------------------------------------------------------------------------
local function sortHere() return not IsAddOnLoaded("ThorControls") end

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_LOGIN")
events:RegisterEvent("ADDON_LOADED")
events:RegisterEvent("BANKFRAME_OPENED")
events:RegisterEvent("BANKFRAME_CLOSED")
events:RegisterEvent("MAIL_SHOW")
events:RegisterEvent("MAIL_CLOSED")
events:RegisterEvent("UI_ERROR_MESSAGE")
events:SetScript("OnEvent", function(self, event, arg1)
    if event == "PLAYER_LOGIN" then
        makeOpenAll()
        if sortHere() and IsAddOnLoaded("Bagnon") then hookBagnon() end
    elseif event == "ADDON_LOADED" then
        if arg1 == "Bagnon" and sortHere() and IsLoggedIn() then hookBagnon() end
    elseif event == "BANKFRAME_OPENED" then
        bankOpen = true
    elseif event == "BANKFRAME_CLOSED" then
        bankOpen = nil
    elseif event == "MAIL_SHOW" then
        makeOpenAll()
        if openAll then placeOpenAll() end
    elseif event == "MAIL_CLOSED" then
        if mailJob then stopMail("Stopped: the mailbox closed.") end
    elseif event == "UI_ERROR_MESSAGE" then
        if mailJob and (arg1 == ERR_INV_FULL or arg1 == ERR_ITEM_MAX_COUNT) then
            stopMail("Stopped: " .. arg1)
        end
    end
end)

SLASH_SORTANDMAIL1 = "/sortandmail"
SlashCmdList.SORTANDMAIL = function(text)
    text = (text or ""):lower():match("^%s*(.-)%s*$")
    if text == "sort" then startSort("bags")
    elseif text == "sort bank" then startSort("bank")
    elseif text == "mail" then
        if MailFrame and MailFrame:IsShown() then startMail() else msg("Open a mailbox first.") end
    else
        msg("/sortandmail sort | sort bank | mail (Open All in the mailbox)")
    end
end
