# Store / Stroe - Admin Seed Data

## 배경

현재 애플리케이션에는 Store 와 Store_Admin 을 생성하는 API 가 존재하지 않는다.

도메인 흐름상 점포와 점포 관리자는 일반 사용자가 직접 생성하는 대상이 아니라, 본사 HQ 에서 생성한 뒤 제공하는 대상이다.

핵심 비즈니스 로직에서는 살짝 벗어나있는 운영과 관련한 기능에 가깝기 때문에 우선순위를 미뤄두도록 한다.

따라서 현재 개발 단계에서는 Store, Store_Admin 데이터를 SQL 로 직접 생성하여 활용하도록 하고 추후 HQ 기능이 구현되면 SQL 직접 입력 방식은 제거한다.

## 현재 방식
1. Store 데이터를 SQL 로 직접 INSERT 한다
2. Store_Admin 데이터를 SQL 로 직접 INSERT 한다.
3. Store_Admin Password 는 BCrypt 로 인코딩된 값을 저장하도록 한다.