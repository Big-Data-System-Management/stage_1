MULTIPLIER = 0x5DEECE66D
ADDEND = 0xB
MASK = (1 << 48) - 1


class JavaRandom:

    def __init__(self, seed):
        self.seed = (seed ^ MULTIPLIER) & MASK

    def next(self, bits):
        self.seed = (self.seed * MULTIPLIER + ADDEND) & MASK
        value = self.seed >> (48 - bits)
        return value - (1 << 32) if value >= 1 << 31 else value

    def next_int(self, bound):
        r = self.next(31)
        m = bound - 1
        if bound & m == 0:
            return (bound * r) >> 31
        u = r
        r = u % bound
        while u - r + m >= 1 << 31:
            u = self.next(31)
            r = u % bound
        return r

    def shuffle(self, items):
        for i in range(len(items), 1, -1):
            j = self.next_int(i)
            items[i - 1], items[j] = items[j], items[i - 1]
