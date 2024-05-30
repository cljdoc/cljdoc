import globals from "globals";
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: {
      globals: {
        ...globals.browser
      }
    },
    rules: {
      "@typescript-eslint/no-unused-expressions": ['error', {allowShortCircuit: true}],
      "@typescript-eslint/no-unused-vars": ['error', {args: "all",
                                                      argsIgnorePattern: "^_",
                                                      caughtErrors: "all",
                                                      caughtErrorsIgnorePattern: "^_",
                                                      destructuredArrayIgnorePattern: "^_",
                                                      varsIgnorePattern: "^_",
                                                      ignoreRestSiblings: true}]
    }
  },
  {
    ignores: [
      "resources-compiled/**",
      "target/**"
    ]
 }
);
