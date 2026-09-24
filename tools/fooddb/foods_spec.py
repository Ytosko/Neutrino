"""
Neutrino built-in food list.

Two kinds of entries:
  USDA(...)  - nutrition comes from USDA FoodData Central SR Legacy (public domain), by FDC id.
  LOCAL(...) - common Bangladeshi / South Asian dishes USDA doesn't cover. Values are per 100 g
               approximations of typical home/restaurant recipes (Neutrino estimates). Improvements
               with sources are very welcome via pull request.

Units map a unit name to grams for ONE unit (e.g. plate=250 means 1 plate = 250 g).
`ml` is density in g/ml for foods that can be measured by volume (enables ml / L).
Aliases are extra search terms (Bangla romanisations, common names).
"""

def USDA(name, cat, fdc, units, aliases=(), default=None, ml=None):
    return dict(src="usda", name=name, cat=cat, fdc=fdc, units=units, aliases=list(aliases), default=default, ml=ml)

def LOCAL(name, cat, kcal, p, c, f, units, aliases=(), default=None, ml=None):
    return dict(src="local", name=name, cat=cat, kcal=kcal, p=p, c=c, f=f, units=units, aliases=list(aliases), default=default, ml=ml)

FOODS = [
    # ---- Rice & grains ------------------------------------------------------------------
    USDA("White rice, cooked", "grain", 168878, {"plate": 250, "cup": 158, "bowl": 200}, ["bhaat", "bhat", "rice", "sada bhat"], ("plate", 1)),
    USDA("Parboiled rice, cooked", "grain", 169708, {"plate": 250, "cup": 158}, ["siddho chal", "boiled rice", "bhaat"], ("plate", 1)),
    USDA("Brown rice, cooked", "grain", 169704, {"plate": 250, "cup": 202}, ["lal chal", "red rice", "brown bhaat"], ("plate", 1)),
    USDA("Sticky rice, cooked", "grain", 168882, {"plate": 250, "cup": 186}, ["short grain rice", "kalijira", "chinigura"], ("cup", 1)),
    USDA("Puffed rice (muri)", "grain", 173912, {"cup": 14, "handful": 10}, ["muri", "puffed rice"], ("cup", 1)),
    USDA("Oats, dry", "grain", 173904, {"cup": 81, "tbsp": 5}, ["oatmeal", "rolled oats"], ("cup", 0.5)),
    USDA("Oatmeal, cooked with water", "grain", 171662, {"bowl": 234, "cup": 234}, ["porridge", "oats"], ("bowl", 1)),
    USDA("Semolina (suji), dry", "grain", 169715, {"cup": 167, "tbsp": 10}, ["suji", "sooji", "rava"], ("tbsp", 2)),
    USDA("Whole wheat flour (atta)", "grain", 168893, {"cup": 120, "tbsp": 8}, ["atta", "flour", "gom"], ("cup", 0.5)),
    USDA("White flour (maida)", "grain", 168894, {"cup": 125, "tbsp": 8}, ["maida", "flour"], ("cup", 0.5)),
    USDA("Pasta, cooked", "grain", 169737, {"plate": 250, "cup": 140}, ["spaghetti", "macaroni"], ("plate", 1)),
    USDA("Egg noodles, cooked", "grain", 168919, {"plate": 250, "cup": 160}, ["noodles", "chowmein noodles"], ("plate", 1)),
    USDA("Rice noodles, cooked", "grain", 168914, {"plate": 250, "cup": 176}, ["vermicelli", "rice vermicelli"], ("plate", 1)),
    USDA("Instant noodles, dry", "grain", 171177, {"piece": 81}, ["maggi", "mama noodles", "ramen", "cup noodles"], ("piece", 1)),
    USDA("Corn flakes", "grain", 174648, {"cup": 28, "bowl": 40}, ["cereal", "cornflakes"], ("bowl", 1)),

    # ---- Breads ---------------------------------------------------------------------------
    USDA("Roti / chapati", "bread", 171844, {"piece": 45}, ["ruti", "roti", "chapati", "atar ruti"], ("piece", 2)),
    USDA("Paratha", "bread", 174076, {"piece": 79}, ["porota", "parotha", "paratha"], ("piece", 1)),
    USDA("Naan", "bread", 171845, {"piece": 90}, ["nan", "naan ruti"], ("piece", 1)),
    USDA("White bread", "bread", 174925, {"slice": 27}, ["pauruti", "bread", "sandwich bread"], ("slice", 2)),
    USDA("Whole wheat bread", "bread", 172688, {"slice": 32}, ["brown bread", "atar pauruti"], ("slice", 2)),
    USDA("Flour tortilla / wrap", "bread", 167535, {"piece": 49}, ["wrap", "tortilla"], ("piece", 1)),
    USDA("Croissant", "bread", 174987, {"piece": 57}, [], ("piece", 1)),

    # ---- Meat & poultry ------------------------------------------------------------------
    USDA("Chicken breast, cooked", "poultry", 171477, {"piece": 120, "cup": 140}, ["murgi", "murgir mangsho", "chicken"], ("piece", 1)),
    USDA("Chicken thigh, cooked", "poultry", 172388, {"piece": 116}, ["murgi", "chicken leg", "rann"], ("piece", 1)),
    USDA("Chicken drumstick, cooked", "poultry", 172376, {"piece": 96}, ["murgi", "leg piece", "drumstick"], ("piece", 1)),
    USDA("Chicken with skin, roasted", "poultry", 171450, {"piece": 90, "cup": 140}, ["murgi", "roast chicken", "grilled chicken"], ("piece", 1)),
    USDA("Chicken liver, cooked", "poultry", 171061, {"piece": 44}, ["kolija", "murgir kolija", "liver"], ("piece", 2)),
    USDA("Duck, cooked", "poultry", 172411, {"piece": 90, "cup": 140}, ["hash", "haser mangsho"], ("piece", 2)),
    USDA("Beef, cooked", "meat", 169494, {"piece": 35, "cup": 140, "bowl": 150}, ["gorur mangsho", "goru", "beef"], ("piece", 4)),
    USDA("Ground beef, cooked", "meat", 171797, {"cup": 140}, ["keema", "qeema", "minced beef"], ("cup", 0.5)),
    USDA("Goat / mutton, cooked", "meat", 175304, {"piece": 35, "cup": 140, "bowl": 150}, ["khasi", "khashir mangsho", "mutton", "goat"], ("piece", 4)),

    # ---- Fish & seafood --------------------------------------------------------------------
    USDA("Carp (rui, katla), cooked", "fish", 174185, {"piece": 80}, ["rui", "rohu", "katla", "mach", "mrigel"], ("piece", 1)),
    USDA("Catfish (pangas), cooked", "fish", 175166, {"piece": 80}, ["pangas", "pangash", "magur", "shing", "mach"], ("piece", 1)),
    USDA("Tilapia, cooked", "fish", 175177, {"piece": 87}, ["telapia", "mach"], ("piece", 1)),
    USDA("Mackerel, cooked", "fish", 175120, {"piece": 88}, ["mach"], ("piece", 1)),
    USDA("Salmon, cooked", "fish", 175168, {"piece": 100}, ["mach"], ("piece", 1)),
    USDA("Sardines, canned", "fish", 175139, {"piece": 12, "can": 92}, ["sardine"], ("piece", 3)),
    USDA("Tuna, canned in water", "fish", 171986, {"can": 165, "cup": 154}, ["tuna"], ("can", 0.5)),
    USDA("Shrimp / prawns, cooked", "fish", 175180, {"piece": 8, "cup": 145}, ["chingri", "golda", "bagda", "prawn"], ("piece", 8)),

    # ---- Eggs & dairy -----------------------------------------------------------------------
    USDA("Egg, boiled", "egg", 173424, {"piece": 50}, ["dim", "sheddho dim", "boiled egg"], ("piece", 1)),
    USDA("Egg, fried", "egg", 173423, {"piece": 46}, ["dim bhaji", "dim poach", "fried egg", "poach"], ("piece", 1)),
    USDA("Egg, scrambled / omelette", "egg", 172187, {"piece": 61, "cup": 220}, ["omlet", "omelette", "dim bhuna", "scrambled"], ("piece", 2)),
    USDA("Egg white", "egg", 172183, {"piece": 33}, ["dimer shada"], ("piece", 2)),
    USDA("Milk, whole", "dairy", 171265, {"glass": 250, "cup": 244, "tbsp": 15}, ["dudh", "milk"], ("glass", 1), ml=1.03),
    USDA("Milk, low fat", "dairy", 170872, {"glass": 250, "cup": 244}, ["low fat dudh", "skimmed milk"], ("glass", 1), ml=1.03),
    USDA("Yogurt, plain (doi)", "dairy", 171284, {"cup": 245, "bowl": 150, "tbsp": 15}, ["doi", "tok doi", "curd", "yogurt"], ("bowl", 1)),
    USDA("Cheddar cheese", "dairy", 170899, {"slice": 21}, ["cheese"], ("slice", 1)),
    USDA("Mozzarella cheese", "dairy", 170845, {"cup": 112, "slice": 28}, ["cheese"], ("slice", 1)),
    USDA("Butter", "fat", 173410, {"tbsp": 14, "tsp": 5}, ["makhon"], ("tsp", 1)),
    USDA("Ghee", "fat", 173412, {"tbsp": 13, "tsp": 4.3}, ["ghee", "ghi"], ("tsp", 1)),
    USDA("Condensed milk, sweetened", "dairy", 171275, {"tbsp": 19}, ["condensed milk"], ("tbsp", 1)),
    USDA("Milk powder, whole", "dairy", 170876, {"tbsp": 8, "cup": 128}, ["guro dudh", "powder milk", "dano", "marks"], ("tbsp", 2)),
    USDA("Ice cream, vanilla", "dessert", 167575, {"scoop": 66, "cup": 132}, ["ice cream"], ("scoop", 1)),

    # ---- Lentils, beans, soy ----------------------------------------------------------------
    USDA("Lentils, cooked (plain)", "legume", 172421, {"bowl": 200, "cup": 198}, ["dal", "daal", "masoor"], ("bowl", 1)),
    USDA("Chickpeas, cooked", "legume", 173757, {"cup": 164, "bowl": 200}, ["chola", "boot", "chana", "chickpea"], ("cup", 1)),
    USDA("Mung beans, cooked", "legume", 174257, {"cup": 202, "bowl": 200}, ["moog dal", "mug dal", "mung dal"], ("bowl", 1)),
    USDA("Split peas, cooked", "legume", 172429, {"cup": 196, "bowl": 200}, ["motor dal", "khesari", "split pea"], ("bowl", 1)),
    USDA("Kidney beans, cooked", "legume", 173740, {"cup": 177}, ["rajma", "beans"], ("cup", 1)),
    USDA("Tofu, firm", "legume", 172448, {"piece": 81, "cup": 252}, ["tofu"], ("piece", 1)),

    # ---- Vegetables ----------------------------------------------------------------------------
    USDA("Potato, boiled", "vegetable", 170440, {"piece": 167, "cup": 156}, ["alu", "aloo", "potato"], ("piece", 1)),
    USDA("Sweet potato, boiled", "vegetable", 168484, {"piece": 151}, ["mishti alu", "shakalu"], ("piece", 1)),
    USDA("Onion, raw", "vegetable", 170000, {"piece": 110, "tbsp": 10}, ["piyaj", "peyaj", "onion"], ("piece", 0.5)),
    USDA("Tomato, raw", "vegetable", 170457, {"piece": 120, "cup": 180}, ["tomato"], ("piece", 1)),
    USDA("Cucumber, raw", "vegetable", 168409, {"piece": 300, "cup": 104}, ["shosha", "sosha", "cucumber"], ("piece", 0.5)),
    USDA("Carrot, raw", "vegetable", 170393, {"piece": 61, "cup": 128}, ["gajor", "carrot"], ("piece", 1)),
    USDA("Cabbage, raw", "vegetable", 169975, {"cup": 89}, ["badhakopi", "bandhakopi", "cabbage"], ("cup", 1)),
    USDA("Cauliflower, cooked", "vegetable", 170397, {"cup": 124, "bowl": 150}, ["fulkopi", "phulkopi", "cauliflower"], ("cup", 1)),
    USDA("Spinach / leafy greens, cooked", "leafy", 168463, {"cup": 180, "bowl": 150}, ["shak", "palong shak", "lal shak", "spinach"], ("cup", 0.5)),
    USDA("Mustard greens, cooked", "leafy", 169257, {"cup": 140}, ["shorisha shak", "sorisha shak"], ("cup", 0.5)),
    USDA("Eggplant, cooked", "vegetable", 169229, {"cup": 99, "piece": 200}, ["begun", "brinjal", "baingan"], ("cup", 1)),
    USDA("Okra, cooked", "vegetable", 169261, {"cup": 160, "piece": 11}, ["dherosh", "bhindi", "ladies finger"], ("cup", 0.5)),
    USDA("Pumpkin, cooked", "vegetable", 168449, {"cup": 245, "bowl": 150}, ["kumra", "misti kumra", "pumpkin"], ("cup", 0.5)),
    USDA("Bitter gourd, cooked", "vegetable", 168496, {"cup": 124}, ["korola", "karela", "uchche"], ("cup", 0.5)),
    USDA("Bottle gourd, cooked", "vegetable", 169353, {"cup": 146, "bowl": 150}, ["lau", "lauki", "kodu"], ("cup", 1)),
    USDA("Green peas, cooked", "vegetable", 170420, {"cup": 160, "tbsp": 10}, ["motorshuti", "peas"], ("cup", 0.5)),
    USDA("Green beans, cooked", "vegetable", 169141, {"cup": 125}, ["borboti", "shim", "beans"], ("cup", 0.5)),
    USDA("Sweet corn, cooked", "vegetable", 169999, {"piece": 103, "cup": 164}, ["bhutta", "corn"], ("piece", 1)),
    USDA("Broccoli, cooked", "vegetable", 169967, {"cup": 156}, ["broccoli"], ("cup", 0.5)),
    USDA("Lettuce", "leafy", 169249, {"cup": 36}, ["salad pata", "lettuce"], ("cup", 1)),
    USDA("Capsicum / bell pepper", "vegetable", 170427, {"piece": 119, "cup": 149}, ["capsicum", "bell pepper"], ("piece", 0.5)),
    USDA("Green chili", "vegetable", 170497, {"piece": 5}, ["kacha morich", "morich", "chili"], ("piece", 2)),
    USDA("Garlic", "vegetable", 169230, {"piece": 3, "tsp": 2.8}, ["rosun", "roshun", "garlic clove"], ("piece", 2)),
    USDA("Ginger", "vegetable", 169231, {"tsp": 2}, ["ada", "ginger"], ("tsp", 1)),
    USDA("Radish", "vegetable", 169276, {"cup": 116, "piece": 150}, ["mula", "radish"], ("cup", 0.5)),
    USDA("Beetroot, cooked", "vegetable", 169146, {"piece": 50, "cup": 170}, ["beet"], ("piece", 1)),
    USDA("Mushrooms", "vegetable", 169251, {"cup": 70}, ["mushroom"], ("cup", 0.5)),

    # ---- Fruits ------------------------------------------------------------------------------
    USDA("Banana", "fruit", 173944, {"piece": 118}, ["kola", "sagor kola", "banana"], ("piece", 1)),
    USDA("Mango", "fruit", 169910, {"piece": 250, "cup": 165}, ["aam", "mango", "himsagar", "langra"], ("piece", 1)),
    USDA("Apple", "fruit", 171688, {"piece": 182, "cup": 125}, ["apel", "apple"], ("piece", 1)),
    USDA("Orange", "citrus", 169097, {"piece": 140}, ["komola", "malta", "orange"], ("piece", 1)),
    USDA("Mandarin", "citrus", 169105, {"piece": 88}, ["komola", "kamla", "mandarin"], ("piece", 1)),
    USDA("Lemon", "citrus", 167746, {"piece": 58, "tbsp": 15}, ["lebu", "lemon"], ("piece", 0.5)),
    USDA("Papaya", "fruit", 169926, {"cup": 145, "piece": 300}, ["pepe", "papaya"], ("cup", 1)),
    USDA("Guava", "fruit", 173044, {"piece": 55, "cup": 165}, ["peyara", "guava"], ("piece", 1)),
    USDA("Pineapple", "fruit", 169124, {"cup": 165, "slice": 84}, ["anaros", "anarosh", "pineapple"], ("cup", 1)),
    USDA("Watermelon", "fruit", 167765, {"cup": 152, "slice": 286}, ["tormuj", "tarmuj", "watermelon"], ("slice", 1)),
    USDA("Jackfruit", "fruit", 174687, {"cup": 165, "piece": 20}, ["kathal", "kanthal", "jackfruit"], ("piece", 5)),
    USDA("Lychee", "fruit", 169086, {"piece": 10, "cup": 190}, ["lichu", "litchi", "lychee"], ("piece", 10)),
    USDA("Grapes", "grape", 174683, {"cup": 151, "piece": 5}, ["angur", "grapes"], ("cup", 1)),
    USDA("Pomegranate", "fruit", 169134, {"piece": 282, "cup": 174}, ["dalim", "anar", "bedana"], ("cup", 0.5)),
    USDA("Pear", "fruit", 169118, {"piece": 178}, ["nashpati", "pear"], ("piece", 1)),
    USDA("Dates", "fruit", 168191, {"piece": 24}, ["khejur", "dates"], ("piece", 3)),
    USDA("Strawberries", "fruit", 167762, {"cup": 152, "piece": 12}, ["strawberry"], ("cup", 1)),
    USDA("Plum", "fruit", 169949, {"piece": 66}, ["alubokhara", "plum"], ("piece", 1)),
    USDA("Kiwi", "fruit", 168153, {"piece": 69}, ["kiwi"], ("piece", 1)),
    USDA("Star fruit", "fruit", 171715, {"piece": 91}, ["kamranga", "star fruit"], ("piece", 1)),
    USDA("Sapodilla", "fruit", 167759, {"piece": 170}, ["sofeda", "chiku", "sapodilla"], ("piece", 1)),
    USDA("Coconut, fresh", "nuts", 170169, {"piece": 45, "cup": 80}, ["narikel", "narkel", "coconut"], ("piece", 1)),
    USDA("Coconut water", "drink", 170174, {"glass": 250, "cup": 240}, ["dab", "daber pani", "coconut water"], ("glass", 1), ml=1.02),

    # ---- Nuts & seeds -----------------------------------------------------------------------------
    USDA("Peanuts, roasted", "nuts", 173806, {"handful": 28, "cup": 146}, ["badam", "chinabadam", "peanut"], ("handful", 1)),
    USDA("Almonds", "nuts", 170567, {"handful": 28, "piece": 1.2}, ["kathbadam", "almond"], ("handful", 1)),
    USDA("Cashews", "nuts", 170162, {"handful": 28, "piece": 1.6}, ["kaju", "kaju badam", "cashew"], ("handful", 1)),
    USDA("Walnuts", "nuts", 170187, {"handful": 28}, ["akhrot", "walnut"], ("handful", 1)),
    USDA("Pistachios", "nuts", 170184, {"handful": 28}, ["pesta", "pistachio"], ("handful", 1)),
    USDA("Peanut butter", "nuts", 172470, {"tbsp": 16}, ["peanut butter"], ("tbsp", 1)),

    # ---- Oils, sugar, condiments -----------------------------------------------------------------
    USDA("Soybean oil", "fat", 171411, {"tbsp": 13.6, "tsp": 4.5}, ["tel", "soyabean tel", "oil", "cooking oil"], ("tbsp", 1), ml=0.92),
    USDA("Mustard oil", "fat", 172337, {"tbsp": 14, "tsp": 4.5}, ["shorisha tel", "sorishar tel", "mustard oil"], ("tbsp", 1), ml=0.92),
    USDA("Olive oil", "fat", 171413, {"tbsp": 13.5, "tsp": 4.5}, ["olive oil"], ("tbsp", 1), ml=0.92),
    USDA("Sugar", "sweet", 169655, {"tsp": 4.2, "tbsp": 12.5}, ["chini", "sugar"], ("tsp", 2)),
    USDA("Brown sugar / jaggery", "sweet", 168833, {"tsp": 4, "tbsp": 12}, ["gur", "akher gur", "jaggery"], ("tbsp", 1)),
    USDA("Honey", "sweet", 169640, {"tbsp": 21, "tsp": 7}, ["modhu", "honey"], ("tbsp", 1)),
    USDA("Jam", "sweet", 169641, {"tbsp": 20}, ["jelly", "jam"], ("tbsp", 1)),
    USDA("Soy sauce", "other", 174278, {"tbsp": 18, "tsp": 6}, ["soy sauce"], ("tbsp", 1)),

    # ---- Snacks, sweets, bakery ---------------------------------------------------------------------
    USDA("Butter cookies / biscuits", "snack", 174950, {"piece": 8}, ["biscuit", "biskut", "cookies"], ("piece", 3)),
    USDA("Crackers", "snack", 174982, {"piece": 3.2}, ["cracker", "salted biscuit"], ("piece", 5)),
    USDA("Cake", "dessert", 173243, {"slice": 50, "piece": 50}, ["cake", "pound cake"], ("slice", 1)),
    USDA("Potato chips", "snack", 169677, {"handful": 28, "piece": 25}, ["chips", "crisps"], ("handful", 1)),
    USDA("Milk chocolate", "sweet", 167587, {"piece": 44}, ["chocolate", "dairy milk"], ("piece", 1)),
    USDA("Dark chocolate", "sweet", 170273, {"piece": 10}, ["dark chocolate"], ("piece", 2)),
    USDA("Doughnut", "dessert", 174992, {"piece": 45}, ["donut", "doughnut"], ("piece", 1)),

    # ---- Drinks ---------------------------------------------------------------------------------------
    USDA("Tea, black (no sugar)", "hotdrink", 173227, {"cup": 180}, ["cha", "raw cha", "lal cha", "tea"], ("cup", 1), ml=1.0),
    USDA("Coffee, black", "hotdrink", 171890, {"cup": 180}, ["coffee", "kofi"], ("cup", 1), ml=1.0),
    USDA("Cola / soft drink", "drink", 174852, {"glass": 250, "can": 330}, ["coke", "pepsi", "cola", "soft drink"], ("can", 1), ml=1.04),
    USDA("Orange juice", "drink", 169098, {"glass": 250}, ["komolar rosh", "juice"], ("glass", 1), ml=1.04),
    USDA("Whey protein powder", "other", 173177, {"scoop": 29}, ["protein powder", "whey"], ("scoop", 1)),

    # ---- Fast food ------------------------------------------------------------------------------------
    USDA("Hamburger", "fastfood", 170693, {"piece": 110}, ["burger", "beef burger"], ("piece", 1)),
    USDA("Cheeseburger", "fastfood", 170690, {"piece": 120}, ["burger", "cheese burger"], ("piece", 1)),
    USDA("Chicken burger / sandwich", "fastfood", 170295, {"piece": 187}, ["chicken burger", "zinger"], ("piece", 1)),
    USDA("French fries", "fastfood", 170698, {"serving": 117}, ["fries", "french fry", "alu fry"], ("serving", 1)),
    USDA("Fried chicken", "fastfood", 170756, {"piece": 140}, ["fried chicken", "kfc", "chicken fry"], ("piece", 1)),
    USDA("Chicken nuggets / strips", "fastfood", 173321, {"piece": 30}, ["nuggets", "chicken strips", "tenders"], ("piece", 4)),
    USDA("Pizza, cheese", "pizza", 170317, {"slice": 107}, ["pizza"], ("slice", 2)),
    USDA("Submarine sandwich", "fastfood", 170709, {"piece": 209}, ["sub", "sandwich"], ("piece", 1)),
    USDA("Fried rice", "ricedish", 167668, {"plate": 300, "cup": 137}, ["fried rice", "chinese rice"], ("plate", 1)),
    USDA("Chicken chow mein", "ricedish", 168083, {"plate": 300}, ["chowmein", "chow mein"], ("plate", 1)),

    # ---- Bangladeshi & South Asian dishes (Neutrino estimates, per 100 g) ------------------------
    LOCAL("Chicken biryani", "ricedish", 190, 8.5, 24, 7, {"plate": 350}, ["biriyani", "biryani", "murgi biryani"], ("plate", 1)),
    LOCAL("Kacchi biryani (mutton)", "ricedish", 215, 9, 23, 10, {"plate": 350}, ["kacchi", "kachchi", "biryani"], ("plate", 1)),
    LOCAL("Beef tehari", "ricedish", 200, 8, 24, 8, {"plate": 300}, ["tehari", "tehri"], ("plate", 1)),
    LOCAL("Morog polao", "ricedish", 195, 9, 24, 7, {"plate": 350}, ["morog polao", "chicken polao"], ("plate", 1)),
    LOCAL("Polao (plain)", "ricedish", 170, 3, 28, 5, {"plate": 250, "cup": 160}, ["polao", "pulao", "pilaf"], ("plate", 1)),
    LOCAL("Khichuri", "ricedish", 150, 4.5, 22, 5, {"plate": 300, "bowl": 250}, ["khichuri", "khichdi", "bhuna khichuri"], ("plate", 1)),
    LOCAL("Panta bhat", "grain", 70, 1.3, 15, 0.2, {"plate": 300}, ["panta", "panta bhaat"], ("plate", 1)),
    LOCAL("Chicken curry", "curry", 145, 14, 4, 8, {"piece": 90, "bowl": 200}, ["murgir jhol", "murgi bhuna", "chicken korma", "chicken curry"], ("piece", 2)),
    LOCAL("Beef curry", "curry", 195, 17, 4, 12, {"piece": 40, "bowl": 200}, ["gorur mangsho bhuna", "beef bhuna", "beef curry", "kala bhuna"], ("bowl", 1)),
    LOCAL("Mutton curry", "curry", 205, 16, 4, 13, {"piece": 40, "bowl": 200}, ["khasir mangsho", "mutton curry", "rezala"], ("bowl", 1)),
    LOCAL("Fish curry (rui / pangas)", "curry", 120, 13, 3, 6, {"piece": 100, "bowl": 200}, ["macher jhol", "mach bhuna", "fish curry"], ("piece", 1)),
    LOCAL("Hilsa, fried", "fish", 310, 22, 1, 24, {"piece": 90}, ["ilish", "ilish bhaja", "hilsa"], ("piece", 1)),
    LOCAL("Hilsa curry", "curry", 250, 18, 3, 18, {"piece": 110}, ["ilish", "shorshe ilish", "ilish jhol"], ("piece", 1)),
    LOCAL("Small fish curry", "curry", 130, 14, 3, 7, {"bowl": 150}, ["choto mach", "mola", "kachki", "puti"], ("bowl", 1)),
    LOCAL("Shrimp curry", "curry", 140, 14, 4, 7.5, {"bowl": 150, "piece": 20}, ["chingri bhuna", "chingri malaikari"], ("bowl", 1)),
    LOCAL("Egg curry", "curry", 150, 10, 4, 11, {"piece": 90}, ["dimer torkari", "dim bhuna", "egg curry"], ("piece", 1)),
    LOCAL("Masoor dal", "legume", 85, 4.5, 11, 2.5, {"bowl": 200, "cup": 240}, ["dal", "daal", "masoor dal", "patla dal"], ("bowl", 1)),
    LOCAL("Thick dal (ghono dal)", "legume", 130, 7, 17, 4, {"bowl": 200}, ["ghono dal", "dal bhuna", "chola dal"], ("bowl", 1)),
    LOCAL("Mixed vegetable curry", "curry", 90, 2, 9, 5, {"bowl": 150, "cup": 150}, ["sobji", "shobji", "torkari", "labra", "vegetable curry"], ("bowl", 1)),
    LOCAL("Potato curry", "curry", 110, 2, 15, 5, {"bowl": 150}, ["alur torkari", "alu dom", "aloo curry"], ("bowl", 1)),
    LOCAL("Aloo bhorta", "curry", 120, 2, 17, 5, {"scoop": 80}, ["alu bhorta", "mashed potato", "bhorta"], ("scoop", 1)),
    LOCAL("Begun bhorta", "curry", 90, 1.5, 7, 6.5, {"scoop": 80}, ["begun bhorta", "baingan bharta", "bhorta"], ("scoop", 1)),
    LOCAL("Shutki bhorta", "curry", 200, 22, 6, 10, {"scoop": 40}, ["shutki", "dried fish"], ("scoop", 1)),
    LOCAL("Shak bhaji", "leafy", 75, 3, 5, 5, {"bowl": 100, "cup": 100}, ["shak bhaji", "lal shak", "palong shak bhaji"], ("bowl", 1)),
    LOCAL("Chicken roast", "poultry", 220, 18, 5, 14, {"piece": 150}, ["roast", "chicken roast", "biye barir roast"], ("piece", 1)),
    LOCAL("Shami kabab", "meat", 250, 16, 10, 16, {"piece": 45}, ["shami kabab", "kabab"], ("piece", 2)),
    LOCAL("Seekh kabab", "meat", 230, 18, 4, 16, {"piece": 50}, ["shik kabab", "sheekh", "kabab"], ("piece", 2)),
    LOCAL("Chicken tikka / grilled", "poultry", 180, 25, 3, 7.5, {"piece": 30}, ["tikka", "grill", "tandoori", "bbq chicken"], ("piece", 4)),
    LOCAL("Haleem", "curry", 140, 9, 14, 5, {"bowl": 250}, ["halim", "haleem"], ("bowl", 1)),
    LOCAL("Nihari", "curry", 180, 14, 4, 12, {"bowl": 250}, ["nehari", "nihari"], ("bowl", 1)),
    LOCAL("Luchi / puri", "bread", 350, 6, 40, 18, {"piece": 25}, ["luchi", "puri", "poori"], ("piece", 3)),
    LOCAL("Dal puri", "bread", 300, 8, 40, 12, {"piece": 60}, ["dalpuri", "dal puri"], ("piece", 2)),
    LOCAL("Singara", "snack", 280, 5, 32, 15, {"piece": 50}, ["shingara", "singara"], ("piece", 2)),
    LOCAL("Samosa", "snack", 300, 6, 30, 17, {"piece": 40}, ["somucha", "samosa"], ("piece", 2)),
    LOCAL("Piyaju / pakora", "snack", 330, 9, 30, 19, {"piece": 20}, ["piyaju", "peyaju", "pakora", "beguni", "chop"], ("piece", 3)),
    LOCAL("Fuchka", "snack", 200, 4, 28, 8, {"piece": 15}, ["fuchka", "phuchka", "pani puri", "golgappa"], ("piece", 8)),
    LOCAL("Chotpoti", "snack", 130, 5, 19, 4, {"bowl": 200}, ["chotpoti", "chatpati"], ("bowl", 1)),
    LOCAL("Jhalmuri", "snack", 400, 8, 60, 14, {"cup": 40, "bowl": 60}, ["jhal muri", "jhalmuri", "muri makha"], ("cup", 1)),
    LOCAL("Chanachur", "snack", 520, 14, 45, 32, {"handful": 30}, ["chanachur", "bombay mix", "mixture"], ("handful", 1)),
    LOCAL("Chira (flattened rice), dry", "grain", 350, 7, 77, 1.2, {"cup": 40}, ["chira", "poha", "flattened rice"], ("cup", 1)),
    LOCAL("Pitha (chitoi / bhapa)", "dessert", 190, 3, 40, 1.5, {"piece": 60}, ["pitha", "chitoi", "bhapa pitha", "puli"], ("piece", 2)),
    LOCAL("Payesh / kheer", "dessert", 150, 4, 23, 5, {"bowl": 150}, ["payesh", "kheer", "firni", "rice pudding"], ("bowl", 1)),
    LOCAL("Semai", "dessert", 190, 4.5, 30, 6, {"bowl": 150}, ["shemai", "semai", "sewai"], ("bowl", 1)),
    LOCAL("Rasgulla / roshogolla", "dessert", 186, 4, 38, 2, {"piece": 40}, ["roshogolla", "rasgulla", "mishti", "sweets"], ("piece", 2)),
    LOCAL("Kalojam / gulab jamun", "dessert", 330, 5, 50, 12, {"piece": 40}, ["kalojam", "gulab jamun", "mishti"], ("piece", 2)),
    LOCAL("Sandesh", "dessert", 300, 9, 45, 9, {"piece": 30}, ["shondesh", "sandesh", "mishti"], ("piece", 2)),
    LOCAL("Mishti doi", "dessert", 150, 4, 23, 4.5, {"cup": 100, "bowl": 150}, ["mishti doi", "sweet yogurt"], ("cup", 1)),
    LOCAL("Milk tea (cha)", "hotdrink", 45, 1, 7, 1.3, {"cup": 120}, ["cha", "dudh cha", "milk tea", "tea"], ("cup", 1), ml=1.03),
    LOCAL("Milk coffee", "hotdrink", 55, 1.8, 7, 2, {"cup": 150}, ["coffee", "milk coffee", "latte"], ("cup", 1), ml=1.03),
    LOCAL("Borhani", "drink", 45, 2, 6, 1.5, {"glass": 200}, ["borhani"], ("glass", 1), ml=1.03),
    LOCAL("Lassi, sweet", "drink", 90, 3, 14, 2.5, {"glass": 250}, ["lassi", "matha"], ("glass", 1), ml=1.04),
    LOCAL("Sugarcane juice", "drink", 70, 0.2, 18, 0, {"glass": 250}, ["akher rosh", "sugarcane"], ("glass", 1), ml=1.05),
]
