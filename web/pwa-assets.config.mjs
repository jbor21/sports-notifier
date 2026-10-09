// The app's icons, generated once from sideline.svg and committed: `pnpm icons` regenerates them.
// Maskable and apple-touch icons get a green background, so the glyph sits on the brand colour
// instead of white when the platform crops or rounds it.
import { defineConfig } from '@vite-pwa/assets-generator/config'

const green = { background: '#16794c', fit: 'contain' }

const names = {
  transparent: 'pwa',
  maskable: 'maskable',
  apple: 'apple-touch-icon',
}

export default defineConfig({
  headLinkOptions: { preset: '2023' },
  preset: {
    transparent: { sizes: [192, 512], favicons: [[48, 'favicon.ico']] },
    maskable: { sizes: [512], padding: 0, resizeOptions: green },
    apple: { sizes: [180], padding: 0, resizeOptions: green },
    assetName: (type, size) => `${names[type]}-${size.width}x${size.height}.png`,
  },
  images: ['public/sideline.svg'],
})
