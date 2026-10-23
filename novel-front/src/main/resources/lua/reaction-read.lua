-- Counts and memberships are one Redis execution snapshot, not a DB/Redis transaction.
-- Existing key names are retained; this two-key script targets the current standalone Redis.
for _, key in ipairs(KEYS) do
    local kind = redis.call('TYPE', key).ok
    if kind ~= 'none' and kind ~= 'set' then
        return redis.error_reply('Reaction key type mismatch')
    end
end
local userId = ARGV[1]
return {redis.call('SCARD', KEYS[1]), redis.call('SCARD', KEYS[2]),
        redis.call('SISMEMBER', KEYS[1], userId), redis.call('SISMEMBER', KEYS[2], userId)}
