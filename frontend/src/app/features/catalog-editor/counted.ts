/** A number of things in words: "no cells", "1 cell", "120 cells". */
export const counted = (count: number, thing: string) =>
  `${count === 0 ? 'no' : count} ${thing}${count === 1 ? '' : 's'}`;

/** The words with a capital first letter, to start a sentence with. */
export const sentence = (words: string) => words.charAt(0).toUpperCase() + words.slice(1);
