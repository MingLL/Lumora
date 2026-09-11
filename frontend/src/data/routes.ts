export interface RouteStop {
  name: string;
  subtitle: string;
  photo: string;
  photoAlt: string;
  tip: string;
  mapX: number;
  mapY: number;
}

export interface CityRoute {
  slug: string;
  title: string;
  shortTitle: string;
  description: string;
  city: string;
  district: string;
  cover: string;
  distance: string;
  duration: string;
  transport: string;
  bestTime: string;
  season: string;
  difficulty: string;
  updatedAt: string;
  tags: string[];
  relatedPostId: string;
  startNavigationUrl: string;
  notes: string[];
  stops: RouteStop[];
}

export const routes: CityRoute[] = [
  {
    slug: 'beijing-tiantan-photography',
    title: '北京天坛公园摄影路线：从东门走到圜丘',
    shortTitle: '天坛公园摄影路线',
    description: '一条约三小时的天坛建筑摄影路线，依次经过长廊、祈年殿、丹陛桥、回音壁和圜丘。',
    city: '北京',
    district: '东城区',
    cover: '/images/posts/street-2022-09-25/cover.webp',
    distance: '约 4 公里',
    duration: '2.5—3.5 小时',
    transport: '地铁 5 号线天坛东门站',
    bestTime: '晴天下午 15:00 至日落前',
    season: '春、秋两季',
    difficulty: '轻松',
    updatedAt: '2026-09-04',
    tags: ['北京', '天坛', '摄影', 'Citywalk'],
    relatedPostId: 'street-2022-09-25',
    startNavigationUrl:
      'https://uri.amap.com/navigation?to=116.4173,39.8830,%E5%A4%A9%E5%9D%9B%E4%B8%9C%E9%97%A8&mode=walk&src=lumora&coordinate=gaode&callnative=1',
    notes: [
      '天坛面积很大，不建议在一个下午里把所有角落都走完。这条路线只保留建筑摄影最集中的中轴线。',
      '祈年殿附近游客最多，先观察门框、树影和台阶形成的前景，比一直等待空场更容易拍出层次。',
      '开放时间和票务可能调整，出发前请以景区当天公告为准；页面中的时间是摄影建议，不是开放时间。'
    ],
    stops: [
      {
        name: '天坛东门',
        subtitle: '从这里进入，先让脚步慢下来',
        photo: '/images/posts/street-2022-09-25/03.webp',
        photoAlt: '天坛公园内的街头纪实照片',
        tip: '出站后从东门进入。刚进园时先留意晨练、园林养护和树影，这里更适合拍生活感的画面。',
        mapX: 82,
        mapY: 13
      },
      {
        name: '长廊',
        subtitle: '利用廊柱制造纵深',
        photo: '/images/posts/street-2022-09-25/04.webp',
        photoAlt: '天坛公园园林与游人',
        tip: '沿长廊向西。低一点的机位能让柱子形成连续框架，等一位游人进入画面再按快门。',
        mapX: 62,
        mapY: 25
      },
      {
        name: '祈年殿',
        subtitle: '先拍局部，再拍完整建筑',
        photo: '/images/posts/street-2022-09-25/01.webp',
        photoAlt: '祈年殿建筑一角',
        tip: '正面机位很经典，也最拥挤。可以用门洞、红墙或汉白玉栏杆做前景，35—50mm 更容易避开杂乱人群。',
        mapX: 43,
        mapY: 34
      },
      {
        name: '丹陛桥',
        subtitle: '沿中轴线向南',
        photo: '/images/posts/street-2022-09-25/05.webp',
        photoAlt: '天坛公园中轴线景色',
        tip: '这一段适合拍对称构图和人物背影。下午向南走时注意侧光落在红墙与古柏上的变化。',
        mapX: 48,
        mapY: 53
      },
      {
        name: '回音壁',
        subtitle: '寻找墙面的弧线与光影',
        photo: '/images/posts/street-2022-09-25/02.webp',
        photoAlt: '天坛公园中的传统艺术细节',
        tip: '这里空间紧凑，广角容易收入太多游客。尝试用中焦截取墙面弧线、门窗和人物之间的关系。',
        mapX: 42,
        mapY: 69
      },
      {
        name: '圜丘',
        subtitle: '在开阔处结束路线',
        photo: '/images/posts/street-2022-09-25/cover.webp',
        photoAlt: '天坛公园摄影路线封面',
        tip: '临近日落时光线更柔和。拍完后可由南门离开；如果体力充足，再沿古柏区慢慢返回。',
        mapX: 51,
        mapY: 88
      }
    ]
  }
];

export const routeForPost = (postId: string) => routes.find((route) => route.relatedPostId === postId);
