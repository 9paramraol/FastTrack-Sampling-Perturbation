#include <iostream>
#include <bits/stdc++.h>
using namespace std;


int main() {
  map<pair<string,string>,int> ab_map;
  string a; string b;
  while(getline(cin, a)){
	  	getline(cin, b);
	  	pair<string,string> temp;
	  	temp.first = a; temp.second = b;
	  	if(ab_map.find(temp)==ab_map.end())
		  	ab_map[temp] = 0;
		ab_map[temp]++;
  } 
  for (auto i : ab_map){ 
        cout << i.first.first << "\n" << i.first.second << "\n" << i.second << endl;
  } 
  cout << ab_map.size() << endl;
  return 0;
}
