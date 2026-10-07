module.exports = {
  preset: '@react-native/jest-preset',
  modulePaths: ['<rootDir>/node_modules'],
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  transformIgnorePatterns: [
    'node_modules/(?!((@)?react-native|react-native-safe-area-context)/)',
  ],
};
