export const site = {
  name: '远方有温度',
  description: '一个普通人的成长、城市与生活记录。',
  author: '温野',
  email: 'ambition1314@icloud.com',
  qrcode: '/images/qrcode/wechat-qrcode.jpg',
  nav: [
    { href: '/', label: '首页' },
    { href: '/archive', label: '文章归档' },
    { href: '/timeline', label: '成长时间线' },
    { href: '/street', label: '扫街周记' },
    { href: '/cities', label: '城市地图' },
    { href: '/about', label: '关于' }
  ]
};

export const categoryDescriptions: Record<string, string> = {
  关于我: '我向往远方，正如同向往生活的美好。',
  成长手记: '从校园到职场，一个普通人的这几年。',
  北京生活: '在北京的三年，从毕业到离开。',
  扫街周记: '带着相机在城市里走，一周一辑。',
  城记远方: '去过的一些城市，和路上的事。',
  年终总结: '每年年末，把这一年重新讲一遍。'
};

// 章节入口的展示顺序与中文序号（章节由本地文章按 category 聚合而成）
export const chapterOrder = ['关于我', '成长手记', '年终总结', '北京生活', '扫街周记', '城记远方'] as const;
export const chapterNumerals = ['壹', '贰', '叁', '肆', '伍', '陆'];

// 首屏文案
export const heroCopy = {
  eyebrow: '个人文集 · 自 2021 年 · 写字，也拍照',
  headline: ['走过一些路，也写下一些字。', '这些年的见闻与心事，都留在这里。'],
  intro:
    '我是温野。自 2021 年起，断断续续地写字，也拍照。这里收着一路上的见闻、思考和生活片段，也记录着一个普通人缓慢生长的痕迹。',
  note: ['慢慢走，', '慢慢写。']
};

export const timeline = [
  {
    year: '2021',
    title: '毕业，去北京',
    description: '从福州毕业，一个人去了北京。开始租房、上班、自己过日子。',
    posts: ['Hello 北京!', '毕业季｜是青春飞舞呀', '你好！北京']
  },
  {
    year: '2022',
    title: '入职、成长、摄影开始',
    description: '工作忙了起来，也买了相机。周末一个人出门，拍到哪算哪。',
    posts: ['我做好了所有准备，站在你面前', '剑未佩妥，出门已是江湖', '扫街周记｜小白入门摄影']
  },
  {
    year: '2023',
    title: '理想生活与城市游走',
    description: '一边上班一边往外跑，宁波、青岛都去了，装备也捡了一路。',
    posts: ['为理想的生活努力，蛮激动的', '城记｜海定则波宁', '我捡了一路的装备']
  },
  {
    year: '2024',
    title: '做决定，走出来',
    description: '三年，赶上三次裁员。想清了所有后果，还是决定回家。',
    posts: ['我想清了所有后果，做了这个决定。', '走出来，走下去']
  },
  {
    year: '2026',
    title: '28岁，继续升级打怪',
    description: '28 岁了，没什么大事，把手里的日子过明白就行。',
    posts: ['28岁是一种什么样的体验呢？']
  }
];

// cover 从该城市文章里挑一张自己拍的照片；留空则回落到 CityCard 的生成封面
export const cities = [
  { name: '北京', description: '待了三年的地方', cover: '/images/posts/ni-hao-beijing/cover.webp' },
  { name: '宁波', description: '海定则波宁', cover: '/images/posts/city-ningbo/cover.webp' },
  { name: '青岛', description: '路是起伏的，海是辽阔的', cover: '/images/posts/street-2023-01-08/01.webp' },
  { name: '西安', description: '毕业旅行的第一站', cover: '' },
  { name: '福州', description: '大学四年，离家两千公里', cover: '' },
  { name: '宁夏', description: '老家', cover: '' }
];
