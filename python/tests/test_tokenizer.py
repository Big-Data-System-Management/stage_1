import unittest

from stage1.tokenizer import Tokenizer


class TokenizerTest(unittest.TestCase):

    tokenizer = Tokenizer()

    def test_lowercases_and_removes_accents(self):
        self.assertEqual(["cafe", "ecole"], self.tokenizer.tokenize("Café ÉCOLE"))

    def test_splits_on_everything_that_is_not_a_letter_or_digit(self):
        self.assertEqual(["sea", "shore", "whale"], self.tokenizer.tokenize("sea-shore, whale's!"))

    def test_removes_stopwords_and_single_character_tokens(self):
        self.assertEqual(["cat", "mat"], self.tokenizer.tokenize("The cat is on a mat"))

    def test_keeps_digits_and_other_alphabets(self):
        self.assertEqual(["1813", "ωμεγα", "straße"], self.tokenizer.tokenize("1813 Ωμέγα Straße"))

    def test_folds_final_sigma(self):
        self.assertEqual(["οδοσ", "οδοσ", "οδοσ", "βασ"], self.tokenizer.tokenize("ΟΔΟΣ οδος ΟΔΟΣ-ΒΑΣ"))

    def test_discards_tokens_longer_than_fifty_characters(self):
        fifty = "a" * 50
        self.assertEqual([fifty], self.tokenizer.tokenize(fifty + " " + fifty + "a"))

    def test_keeps_order_and_repetitions(self):
        self.assertEqual(["whale", "sea", "whale"], self.tokenizer.tokenize("whale sea whale"))

    def test_returns_empty_list_for_none_or_blank_text(self):
        self.assertEqual([], self.tokenizer.tokenize(None))
        self.assertEqual([], self.tokenizer.tokenize("  ,;  "))

    def test_uses_the_given_stopwords(self):
        self.assertEqual(["the", "sea"], Tokenizer({"whale"}).tokenize("the whale sea"))


if __name__ == "__main__":
    unittest.main()
