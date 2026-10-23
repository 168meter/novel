-- Counts and memberships are one Redis execution snapshot, not a DB/Redis transaction.
-- Existing key names are retained; this two-key script targets the current standalone Redis.
for _, key in ipairs(KEYS) do
    local kind = redis.call('TYPE', key).ok
    if kind ~= 'none' and kind ~= 'set' then
        return redis.error_reply('Reaction key type mismatch')
    end
end
local userId = ARGV[1]
-- Validate before the first mutation: Lua runtime errors do not roll back earlier writes.
local index = tonumber(ARGV[2])
if (index ~= 1 and index ~= 2) or (ARGV[3] ~= '1' and ARGV[3] ~= '0') then
    return redis.error_reply('Invalid reaction state')
end
if ARGV[3] == '1' then
    redis.call('SADD', KEYS[index], userId)
else
    redis.call('SREM', KEYS[index], userId)
end
return {redis.call('SCARD', KEYS[1]), redis.call('SCARD', KEYS[2]),
        redis.call('SISMEMBER', KEYS[1], userId), redis.call('SISMEMBER', KEYS[2], userId)}
