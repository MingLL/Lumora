/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{astro,html,js,jsx,md,mdx,svelte,ts,tsx,vue}'],
  theme: {
    extend: {
      colors: {
        paper: '#FAF8F3',
        warm: '#F7F3EA',
        ink: '#2B2B2B',
        muted: '#77736B',
        accent: '#B8794C',
        olive: '#8A6F4D',
        line: '#E8E0D2',
        card: '#FDFBF7'
      },
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'Noto Sans SC',
          'Microsoft YaHei',
          'sans-serif'
        ],
        serif: [
          'Noto Serif SC',
          'Songti SC',
          'STSong',
          'Georgia',
          'serif'
        ]
      },
      boxShadow: {
        soft: '0 18px 50px rgba(64, 47, 31, 0.08)'
      }
    }
  },
  plugins: []
};
